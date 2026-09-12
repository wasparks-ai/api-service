package com.wasparks.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two public surfaces (epic §B2, §B9): the health endpoint compose and nginx depend on, and the
 * documentation nginx maps developers.wasparks.com to.
 *
 * <p>Worth testing because both are easy to break invisibly. A change to the security chain that made
 * {@code /actuator/health} require a key would fail every container health check and take the service
 * out of the load balancer; a SpringDoc group that silently matched nothing would leave the docs site
 * showing an empty API, and nobody would notice until a developer tried to use it.
 */
class DocsAndHealthIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("health is public and reports UP")
    void health() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("the meta group documents the Meta-compatible send endpoint")
    void metaGroup() throws Exception {
        mockMvc.perform(get("/v3/api-docs/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/meta/whatsapp/{version}/{phoneNumberId}/messages']")
                        .exists())
                .andExpect(jsonPath("$.info.title").value("WaSparks API"));
    }

    @Test
    @DisplayName("the v1 group documents our own surface and nothing from /meta")
    void v1Group() throws Exception {
        mockMvc.perform(get("/v3/api-docs/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/messages/{id}']").exists())
                .andExpect(jsonPath("$.paths['/v1/account']").exists())
                .andExpect(jsonPath("$.paths['/v1/webhooks']").exists())
                .andExpect(jsonPath("$.paths['/v1/keys']").exists())
                .andExpect(jsonPath("$.paths['/v1/templates']").exists())
                .andExpect(jsonPath("$.paths['/meta/whatsapp/{version}/{phoneNumberId}/messages']")
                        .doesNotExist());
    }

    @Test
    @DisplayName("both authentication schemes are documented")
    void securitySchemes() throws Exception {
        // A developer reading the docs has to be able to tell that /v1/keys takes their session token
        // while everything else takes an API key — it is the single most confusing thing about this API.
        mockMvc.perform(get("/v3/api-docs/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.ApiKey.name").value("X-API-Key"))
                .andExpect(jsonPath("$.components.securitySchemes.TenantJwt.scheme").value("bearer"));
    }

    @Test
    @DisplayName("the docs UI is reachable without a key")
    void docsUiIsPublic() throws Exception {
        // springdoc.swagger-ui.path=/docs — nginx maps developers.wasparks.com here, so it must not
        // sit behind authentication.
        mockMvc.perform(get("/docs"))
                .andExpect(status().is3xxRedirection());
    }
}
