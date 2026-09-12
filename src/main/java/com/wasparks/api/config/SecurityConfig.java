package com.wasparks.api.config;

import com.wasparks.api.auth.ApiKeyAuthFilter;
import com.wasparks.api.auth.ApiKeyService;
import com.wasparks.api.auth.TenantJwtAuthFilter;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiErrorWriter;
import com.wasparks.api.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Two filter chains, exactly as the epic specifies (§B2).
 *
 * <ul>
 *   <li><b>Order 1 — {@code /v1/keys/**}</b>: the tenant JWT. This is the bootstrap surface, the only
 *       place a session token is accepted, and it must be matched <em>before</em> the key chain or a
 *       user with no key could never create their first one.</li>
 *   <li><b>Order 2 — everything else</b>: the API key. {@code /actuator/health} and the docs are public;
 *       nothing else is.</li>
 * </ul>
 *
 * <p>Both chains are stateless with CSRF off, which is correct for a token API and would be a serious
 * mistake for a cookie one: there is no ambient credential a browser could be tricked into replaying.
 *
 * <p>The entry point and access-denied handler are wired to {@link ApiErrorWriter} so an unauthenticated
 * or unauthorised request gets the same JSON dialect as every other rejection, Meta-shaped under
 * {@code /meta/**}, rather than Tomcat's HTML page.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Open to the world: liveness for compose/nginx, and the developer documentation. */
    public static final String[] PUBLIC_PATHS = {
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/docs", "/docs/**", "/v3/api-docs", "/v3/api-docs/**",
            "/swagger-ui.html", "/swagger-ui/**"
    };

    /** The JWT chain's territory, and nothing beyond it. */
    public static final String KEYS_PATTERN = "/v1/keys/**";

    @Bean
    @Order(1)
    public SecurityFilterChain keyManagementChain(HttpSecurity http, ApiErrorWriter errorWriter,
                                                  @Value("${app.jwt.secret}") String jwtSecret,
                                                  @Value("${app.jwt.issuer}") String jwtIssuer)
            throws Exception {
        TenantJwtAuthFilter jwtFilter = new TenantJwtAuthFilter(errorWriter, jwtSecret, jwtIssuer);
        jwtFilter.init();

        http.securityMatcher(KEYS_PATTERN)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(jwtFilter, AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint(errorWriter))
                        .accessDeniedHandler(accessDenied(errorWriter)));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiKeyChain(HttpSecurity http, ApiKeyService apiKeyService,
                                           ApiErrorWriter errorWriter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyService, errorWriter),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint(errorWriter))
                        .accessDeniedHandler(accessDenied(errorWriter)));
        return http.build();
    }

    /** No credential at all, or one the filter chose to ignore, on a path that needs one. */
    private AuthenticationEntryPoint entryPoint(ApiErrorWriter writer) {
        return (request, response, ex) -> writer.write(request, response,
                ApiException.of(ApiErrorCode.INVALID_API_KEY,
                        "Provide your API key in the X-API-Key header or as a Bearer token."));
    }

    /**
     * Authenticated but not permitted — in practice a {@code @PreAuthorize} scope gate. Reported as
     * {@code insufficient_scope} rather than a bare 403, so the client can tell "this key needs another
     * scope" from "this tenant may not do that at all".
     */
    private AccessDeniedHandler accessDenied(ApiErrorWriter writer) {
        return (request, response, ex) -> writer.write(request, response,
                ApiException.of(ApiErrorCode.INSUFFICIENT_SCOPE));
    }
}
