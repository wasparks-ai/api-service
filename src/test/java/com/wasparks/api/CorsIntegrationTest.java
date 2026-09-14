package com.wasparks.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browser preflight on {@code /v1/**} for tenant-web (follow-up to the api-ecosystem epic).
 *
 * <p>{@code SecurityConfig} wires one {@code CorsConfigurationSource} scoped to {@code /v1/**} into
 * both filter chains, so an allowed origin has to work whether the target sits behind the API-key
 * chain ({@code /v1/account}) or the JWT chain ({@code /v1/keys}) — and any other origin has to be
 * refused on both. {@code app.cors.allowed-origins} in {@code application-test.yml} is
 * {@code http://localhost:5173}, tenant-web's dev origin.
 */
class CorsIntegrationTest extends BaseIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String DISALLOWED_ORIGIN = "https://evil.example";

    @Test
    @DisplayName("preflight from the allowed origin passes on the API-key chain")
    void allowedOriginPreflightPasses() throws Exception {
        mockMvc.perform(options("/v1/account")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("preflight from a disallowed origin is refused on the API-key chain")
    void disallowedOriginPreflightFails() throws Exception {
        mockMvc.perform(options("/v1/account")
                        .header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("preflight from the allowed origin passes on the JWT chain")
    void allowedOriginPreflightPassesOnKeysChain() throws Exception {
        mockMvc.perform(options("/v1/keys")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("/meta/** stays CORS-disabled even for the allowed origin")
    void metaStaysCorsDisabled() throws Exception {
        mockMvc.perform(options("/meta/whatsapp/v20.0/1234567890/messages")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
