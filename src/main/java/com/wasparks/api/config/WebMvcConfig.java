package com.wasparks.api.config;

import com.wasparks.api.auth.ScopeInterceptor;
import com.wasparks.api.idempotency.BodyCachingFilter;
import com.wasparks.api.idempotency.IdempotencyInterceptor;
import com.wasparks.api.quota.QuotaInterceptor;
import com.wasparks.api.ratelimit.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The request pipeline, in the order the epic fixes (hand-off §4):
 * <b>auth → rate limit → scope → idempotency → quota → handler</b>.
 *
 * <p>Order is not cosmetic here. Each stage can consume something the next one would otherwise waste:
 * an over-limit request should not reserve an idempotency key, a mis-scoped request should not eat a
 * tenant's message quota, and a replayed request should consume neither. Registering them in this
 * sequence is what makes those statements true, because {@code HandlerInterceptor}s run strictly in
 * registration order.
 *
 * <p>Idempotency and quota are scoped to the send paths only. Everything else is cheap and repeatable;
 * only a send costs money and can duplicate a customer-visible message.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * The Meta-compatible send endpoint — the only path in v1 that creates a message, and therefore the
     * only one that needs idempotency and quota.
     */
    public static final String SEND_PATH_PATTERN = "/meta/whatsapp/*/*/messages";

    /** Paths that never carry an API principal and must not be charged for one. */
    private static final String[] PIPELINE_EXCLUDES = {
            "/actuator/**", "/docs", "/docs/**", "/v3/api-docs", "/v3/api-docs/**",
            "/swagger-ui.html", "/swagger-ui/**", "/error"
    };

    private final RateLimitInterceptor rateLimitInterceptor;
    private final ScopeInterceptor scopeInterceptor;
    private final IdempotencyInterceptor idempotencyInterceptor;
    private final QuotaInterceptor quotaInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(PIPELINE_EXCLUDES)
                .order(1);

        registry.addInterceptor(scopeInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(PIPELINE_EXCLUDES)
                .order(2);

        registry.addInterceptor(idempotencyInterceptor)
                .addPathPatterns(SEND_PATH_PATTERN)
                .order(3);

        registry.addInterceptor(quotaInterceptor)
                .addPathPatterns(SEND_PATH_PATTERN)
                .order(4);
    }

    /**
     * Body caching for idempotency hashing.
     *
     * <p>Order 0 puts it after Spring Security's chain (which registers at -100), so an unauthenticated
     * request never gets its body buffered — otherwise anyone who can reach the port could make this
     * service hold bytes in memory for them.
     */
    @Bean
    public FilterRegistrationBean<BodyCachingFilter> bodyCachingFilter() {
        FilterRegistrationBean<BodyCachingFilter> registration =
                new FilterRegistrationBean<>(new BodyCachingFilter());
        registration.addUrlPatterns("/meta/*", "/v1/*");
        registration.setOrder(0);
        return registration;
    }
}
