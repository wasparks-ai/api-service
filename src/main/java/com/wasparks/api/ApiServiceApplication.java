package com.wasparks.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * wasparks-api-service — the public developer API (api-ecosystem epic §B), port 8083, public host
 * developer.wasparks.com.
 *
 * <p>It is a <b>gateway, not a second send path</b> (§0.2): every WhatsApp action is performed by
 * tenants-service over {@code /internal/v1/**}. What lives here is everything tenants-service must not
 * learn about — API keys, plans, rate limits, quotas, idempotency, the send queue, usage rollups and
 * outbound webhooks. It is also the only component in the estate that talks to Redis (§0.1).
 */
@SpringBootApplication
public class ApiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiServiceApplication.class, args);
    }
}
