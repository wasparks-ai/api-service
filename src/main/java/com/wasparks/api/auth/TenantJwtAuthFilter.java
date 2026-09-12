package com.wasparks.api.auth;

import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiErrorWriter;
import com.wasparks.api.error.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a tenants-service access token, for {@code /v1/keys/**} only (epic §0.12).
 *
 * <p>tenant-web needs to create and revoke API keys, and the user doing it is signed in with a normal
 * tenant session — asking them to hold an API key in order to create an API key is a bootstrap problem
 * with no answer. So this service validates the same HS256 token tenants-service issues, with the same
 * {@code TENANTS_JWT_SECRET} and the same issuer, and accepts it on that one path.
 *
 * <p>It <b>verifies and never issues</b>. There is no signing key use here, no refresh handling, and no
 * database read for the user: a token this service cannot verify is simply not a caller. Only
 * {@code TENANT_OWNER} and {@code TENANT_ADMIN} pass — a MEMBER holding a valid token is rejected,
 * because key management is not a member capability (epic §D).
 */
@Slf4j
public class TenantJwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final Set<String> ALLOWED_ROLES = Set.of("TENANT_OWNER", "TENANT_ADMIN");

    /** Mirrors tenants-service's own guard: this exact string must never be the prod secret. */
    static final String DEV_DEFAULT_SECRET =
            "tenants-service-256-bit-secret-key-must-be-at-least-32-characters-long";

    private final ApiErrorWriter errorWriter;
    private final String secret;
    private final String issuer;
    private SecretKey key;

    public TenantJwtAuthFilter(ApiErrorWriter errorWriter,
                               @Value("${app.jwt.secret}") String secret,
                               @Value("${app.jwt.issuer}") String issuer) {
        this.errorWriter = errorWriter;
        this.secret = secret;
        this.issuer = issuer;
    }

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extract(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String role = claims.get("role", String.class);
            if (!ALLOWED_ROLES.contains(role)) {
                throw ApiException.of(ApiErrorCode.FORBIDDEN,
                        "Only a tenant owner or admin can manage API keys.");
            }

            String tenantId = claims.get("tenantId", String.class);
            if (tenantId == null) {
                throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Token is missing the tenant claim.");
            }

            TenantUserPrincipal principal = new TenantUserPrincipal(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(tenantId),
                    role,
                    claims.get("email", String.class));

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        } catch (ApiException ex) {
            SecurityContextHolder.clearContext();
            errorWriter.write(request, response, ex);
            return;
        } catch (JwtException | IllegalArgumentException ex) {
            // Expired, wrong issuer, bad signature, unparseable claim — all one answer to the caller.
            log.debug("Rejected tenant JWT: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
            errorWriter.write(request, response,
                    ApiException.of(ApiErrorCode.UNAUTHORIZED, "The access token is not valid."));
            return;
        }

        chain.doFilter(request, response);
    }

    /** A bearer value starting {@code wsk_} is an API key, not a JWT — left for the other chain. */
    private String extract(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return null;
        }
        String token = authorization.substring(BEARER.length()).trim();
        return token.startsWith("wsk_") || token.isEmpty() ? null : token;
    }
}
