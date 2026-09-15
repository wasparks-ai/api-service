package com.wasparks.api.auth;

import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.partner.PartnerTenantResolver;
import com.wasparks.api.error.ApiErrorWriter;
import com.wasparks.api.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates a request by API key (epic §B2).
 *
 * <p>Two header forms are accepted and they are not interchangeable by accident:
 * {@code X-API-Key: wsk_live_…} is what our own documentation teaches, and
 * {@code Authorization: Bearer wsk_live_…} exists because a client migrating from Meta's Cloud API
 * already sends a bearer token and should only have to change the base URL and the value (epic §0.4).
 *
 * <p>A request with no key at all is passed through unauthenticated rather than rejected here. Spring
 * Security's authorization rules decide whether that path required a key, which keeps the public
 * endpoints ({@code /actuator/health}, {@code /docs}) out of this filter's business.
 *
 * <p><b>{@code X-Tenant-Id} is applied here, not in a controller</b> (api-partner epic §B1). A partner
 * key reaches many tenants, and which one a request is about has to be settled before anything — the
 * rate limiter, the scope gate, the quota counters — has run, or those stages would charge the wrong
 * tenant and a controller would be free to forget the check. Resolving it in the filter keeps
 * {@code principal.tenantId()} the single answer to "whose data is this?" for partner and tenant keys
 * alike; see {@link PartnerTenantResolver}.
 */
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    /** The client a partner key is acting for. Ignored on an ordinary tenant key (§0.8). */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    private static final String BEARER = "Bearer ";
    /** Both key modes share this prefix; a bearer token without it is a JWT, not an API key. */
    private static final String KEY_PREFIX = "wsk_";

    private final ApiKeyService apiKeyService;
    private final PartnerTenantResolver partnerTenantResolver;
    private final ApiErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = extract(request);
        if (presented == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            ApiPrincipal principal = partnerTenantResolver.resolve(
                    apiKeyService.resolve(presented), request.getHeader(HEADER_TENANT_ID));
            SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication(principal));
            // Stashed for the interceptors, which run outside the security chain's reach on some error
            // paths and must see the ACTING tenant rather than re-deriving the key's own.
            request.setAttribute(CurrentPrincipal.ATTRIBUTE, principal);
            // Advisory, throttled to one write per key per minute — see ApiKeyService.touchLastUsed.
            apiKeyService.touchLastUsed(principal.keyId());
        } catch (ApiException ex) {
            SecurityContextHolder.clearContext();
            errorWriter.write(request, response, ex);
            return;
        } catch (Exception ex) {
            // Redis or the database being unreachable is a 500, not a 401: telling a client its key is
            // invalid when the truth is that we cannot check would send it off rotating a good key.
            log.error("API key authentication failed unexpectedly", ex);
            SecurityContextHolder.clearContext();
            errorWriter.write(request, response, ApiException.of(ApiErrorCode.INTERNAL_ERROR));
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * {@code X-API-Key} wins when both are present. A bearer value that is not one of our keys is
     * ignored rather than rejected, so the JWT chain on {@code /v1/keys/**} can read the same header.
     */
    private String extract(HttpServletRequest request) {
        String direct = request.getHeader(HEADER);
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER)) {
            String token = authorization.substring(BEARER.length()).trim();
            if (token.startsWith(KEY_PREFIX)) {
                return token;
            }
        }
        return null;
    }
}
