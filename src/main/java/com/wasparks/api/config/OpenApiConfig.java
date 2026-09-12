package com.wasparks.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger UI at {@code /docs}, served to developers.wasparks.com (epic §B9).
 *
 * <p>Two groups, because the API genuinely has two audiences. {@code meta} is for someone porting
 * existing Meta Cloud API code and wants to see one endpoint that looks exactly like the one they
 * already call. {@code v1} is for everything Meta has no shape for — keys, templates, webhooks, usage.
 * Presenting them as one flat list would bury the migration path in our own surface.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI apiInfo(@Value("${app.public-base-url}") String publicBaseUrl) {
        SecurityScheme apiKey = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-API-Key")
                .description("""
                        Your API key, created in WaSparks under Developer → API keys. Shown once.
                        Meta Cloud API clients may send it as `Authorization: Bearer wsk_live_…`
                        instead — both are accepted on every endpoint except /v1/keys.
                        A `wsk_test_` key behaves identically but never reaches WhatsApp.""");

        SecurityScheme tenantJwt = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("A WaSparks tenant access token. Accepted on /v1/keys only.");

        return new OpenAPI()
                .info(new Info()
                        .title("WaSparks API")
                        .version("v1")
                        .description("""
                                Send WhatsApp messages, manage templates and receive delivery webhooks.

                                **Sends are asynchronous.** An accepted send returns `202` with our message
                                id and `message_status: accepted`; the Meta `wamid` and the final outcome
                                arrive by webhook, or from `GET /v1/messages/{id}`. Anything we can reject
                                up front — a bad number, an unapproved template, a closed 24-hour window,
                                a suppressed recipient, no quota left — comes back as a synchronous 4xx,
                                never as a 202.

                                **Rate limits** are per key, per minute, and every response carries
                                `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset`.

                                **Send `Idempotency-Key`** on every send. A retry with the same key and
                                body replays the original response instead of sending twice.""")
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url(publicBaseUrl).description("WaSparks API")))
                .components(new Components()
                        .addSecuritySchemes("ApiKey", apiKey)
                        .addSecuritySchemes("TenantJwt", tenantJwt))
                .addSecurityItem(new SecurityRequirement().addList("ApiKey"));
    }

    /** The Meta Cloud API-compatible surface: one endpoint, deliberately. */
    @Bean
    public GroupedOpenApi metaGroup() {
        return GroupedOpenApi.builder()
                .group("meta")
                .displayName("Meta-compatible send")
                .pathsToMatch("/meta/**")
                .build();
    }

    /** Everything Meta has no shape for. */
    @Bean
    public GroupedOpenApi v1Group() {
        return GroupedOpenApi.builder()
                .group("v1")
                .displayName("WaSparks v1")
                .pathsToMatch("/v1/**")
                .build();
    }
}
