package com.wasparks.api.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.auth.ApiKeyAuthFilter;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.quota.QuotaService;
import com.wasparks.api.repository.ApiOutboxEventRepository;
import com.wasparks.api.webhook.WebhookEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Campaigns (§B3): local validation, the inline cap, and the quota reservation at create, at start and
 * at scheduled-start time.
 *
 * <p>The reservation cases carry the weight. A campaign is the only request on this API that can spend a
 * partner's whole day in one call, so "how many, and when" has to be exactly right — and the release on
 * an upstream refusal has to be exactly right too, or a partner whose template was rejected loses its
 * allowance to a campaign that never existed.
 */
class CampaignsApiIntegrationTest extends BaseIntegrationTest {

    @MockBean
    private InternalTenantsClient tenantsClient;

    @Autowired
    private QuotaService quotaService;
    @Autowired
    private ScheduledCampaignQuotaJob scheduledQuotaJob;
    @Autowired
    private ApiOutboxEventRepository outboxEventRepository;

    private static final String VALID = """
            {"name":"New listing",
             "template":{"name":"new_listing","language":"en_US"},
             "audience":{"recipients":[{"phone":"919876543210","vars":{"1":"Rahul"}}]},
             "clientRef":"listing-4711","respectQuietHours":true}""";

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("a campaign with no template is refused before it crosses the wire")
    void templateRequired() throws Exception {
        String key = issueLiveKey();
        mockMvc.perform(post("/v1/campaigns").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","audience":{"recipients":[]}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        Mockito.verifyNoInteractions(tenantsClient);
    }

    @Test
    @DisplayName("two audience sources, or none, is a 400 naming what was sent")
    void exactlyOneAudienceSource() throws Exception {
        String key = issueLiveKey();
        mockMvc.perform(post("/v1/campaigns").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","template":{"name":"t","language":"en_US"},
                                 "audience":{"recipients":[],"audienceId":"aud_1"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.sourcesGiven").value(2));
    }

    @Test
    @DisplayName("headerMedia without a link is refused with the field named")
    void headerMediaNeedsALink() throws Exception {
        String key = issueLiveKey();
        mockMvc.perform(post("/v1/campaigns").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","template":{"name":"t","language":"en_US",
                                 "headerMedia":{"type":"image"}},
                                 "audience":{"recipients":[{"phone":"9198"}]}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("headerMedia")));
    }

    @Test
    @DisplayName("a button param without an index is refused")
    void buttonParamsNeedAnIndex() throws Exception {
        String key = issueLiveKey();
        mockMvc.perform(post("/v1/campaigns").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","template":{"name":"t","language":"en_US",
                                 "buttonParams":[{"type":"url","value":"abc"}]},
                                 "audience":{"recipients":[{"phone":"9198"}]}}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("more than 5,000 inline recipients is refused with the limit reported")
    void inlineCap() throws Exception {
        String key = issueLiveKey();
        StringBuilder recipients = new StringBuilder();
        for (int i = 0; i < 5001; i++) {
            recipients.append(i == 0 ? "" : ",").append("{\"phone\":\"91987654").append(i % 10)
                    .append("\"}");
        }
        mockMvc.perform(post("/v1/campaigns").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","template":{"name":"t","language":"en_US"},
                                 "audience":{"recipients":[%s]}}""".formatted(recipients)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.limit").value(5000))
                .andExpect(jsonPath("$.error.details.given").value(5001));
    }

    @Test
    @DisplayName("respectQuietHours is accepted and forwarded — reserved, not enforced")
    void quietHoursIsAcceptedAndForwarded() throws Exception {
        String key = issueLiveKey();
        Mockito.when(tenantsClient.createCampaign(Mockito.any(), Mockito.any()))
                .thenReturn(campaign("DRAFT", 10, 0, 0, 0));

        mockMvc.perform(post("/v1/campaigns").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated());

        org.mockito.ArgumentCaptor<JsonNode> sent =
                org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(tenantsClient).createCampaign(Mockito.any(), sent.capture());
        assertThat(sent.getValue().get("respectQuietHours").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ quota at create

    @Test
    @DisplayName("an immediate campaign reserves total minus suppressed, frequency-skipped and invalid")
    void reservesSendableOnly() throws Exception {
        String key = issueLiveKey();
        Mockito.when(tenantsClient.createCampaign(Mockito.any(), Mockito.any()))
                .thenReturn(campaign("DRAFT", 100, 12, 5, 3));

        mockMvc.perform(post("/v1/campaigns").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quotaReserved").value(80));

        // 100 − 12 suppressed − 5 frequency-skipped − 3 invalid. Charging for any of those would bill a
        // partner for messages our own rules forbid us to send.
        assertThat(quotaService.peek(tenantId).today()).isEqualTo(80);
    }

    @Test
    @DisplayName("a SCHEDULED campaign reserves nothing at create — its day has not started")
    void scheduledReservesNothingAtCreate() throws Exception {
        String key = issueLiveKey();
        Mockito.when(tenantsClient.createCampaign(Mockito.any(), Mockito.any()))
                .thenReturn(campaign("SCHEDULED", 100, 0, 0, 0));

        mockMvc.perform(post("/v1/campaigns").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated());

        assertThat(quotaService.peek(tenantId).today()).isZero();
    }

    @Test
    @DisplayName("an over-quota campaign is 429 and is cancelled upstream rather than stranded")
    void overQuotaCreateIsUnwound() throws Exception {
        createPlan("CAMPAIGN_TINY", 1000, 10, 1000, "BLOCK", 3);
        assignPlan("CAMPAIGN_TINY", null);
        String key = issueLiveKey();

        Mockito.when(tenantsClient.createCampaign(Mockito.any(), Mockito.any()))
                .thenReturn(campaign("DRAFT", 50, 0, 0, 0));

        mockMvc.perform(post("/v1/campaigns").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("quota_exceeded"))
                .andExpect(jsonPath("$.error.details.requested").value(50));

        Mockito.verify(tenantsClient).transitionCampaign(Mockito.any(), Mockito.any(),
                Mockito.anyString(), Mockito.eq("cancel"));
        assertThat(quotaService.peek(tenantId).today()).isZero();
    }

    // ------------------------------------------------------------------ quota at start

    @Test
    @DisplayName("start reserves before the transition, and releases when upstream refuses it")
    void startReservesAndReleases() throws Exception {
        String key = issueLiveKey();
        Mockito.when(tenantsClient.getCampaign(Mockito.any(), Mockito.any(), Mockito.anyString()))
                .thenReturn(campaign("DRAFT", 30, 0, 0, 0));
        Mockito.when(tenantsClient.transitionCampaign(Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.eq("start")))
                .thenThrow(new UpstreamRejectedException("campaign_state",
                        "The campaign is not in a state that allows that.", 409));

        mockMvc.perform(post("/v1/campaigns/" + UUID.randomUUID() + "/start")
                        .header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("campaign_state"));

        // Nothing was sent, so nothing may be charged. A start refused for a wrong state must leave the
        // allowance exactly as it found it.
        assertThat(quotaService.peek(tenantId).today()).isZero();
    }

    // ------------------------------------------------------------------ the scheduled sweep

    @Test
    @DisplayName("a campaign due within the window has its quota reserved by the sweep")
    void sweepReservesDueCampaigns() {
        String key = issueLiveKey();
        UUID apiKeyId = apiKeyRepository.findAll().get(0).getId();
        UUID campaignId = UUID.randomUUID();

        Mockito.when(tenantsClient.listCampaigns(Mockito.eq(tenantId), Mockito.any(), Mockito.any()))
                .thenReturn(page(campaignId, apiKeyId, Instant.now().plus(2, ChronoUnit.MINUTES),
                        200));

        assertThat(scheduledQuotaJob.sweep()).isEqualTo(1);
        assertThat(quotaService.peek(tenantId).today()).isEqualTo(200);
        assertThat(key).isNotNull();
    }

    @Test
    @DisplayName("a campaign outside the window is left alone")
    void sweepIgnoresDistantCampaigns() {
        issueLiveKey();
        UUID apiKeyId = apiKeyRepository.findAll().get(0).getId();

        Mockito.when(tenantsClient.listCampaigns(Mockito.eq(tenantId), Mockito.any(), Mockito.any()))
                .thenReturn(page(UUID.randomUUID(), apiKeyId,
                        Instant.now().plus(3, ChronoUnit.DAYS), 200));

        assertThat(scheduledQuotaJob.sweep()).isZero();
        assertThat(quotaService.peek(tenantId).today()).isZero();
    }

    @Test
    @DisplayName("an over-quota scheduled campaign is paused upstream with reason QUOTA")
    void sweepPausesOverQuotaCampaigns() {
        createPlan("CAMPAIGN_TINY_SWEEP", 1000, 10, 1000, "BLOCK", 3);
        assignPlan("CAMPAIGN_TINY_SWEEP", null);
        issueLiveKey();
        UUID apiKeyId = apiKeyRepository.findAll().get(0).getId();
        UUID campaignId = UUID.randomUUID();

        Mockito.when(tenantsClient.listCampaigns(Mockito.eq(tenantId), Mockito.any(), Mockito.any()))
                .thenReturn(page(campaignId, apiKeyId, Instant.now().plus(1, ChronoUnit.MINUTES), 50));

        scheduledQuotaJob.sweep();

        // Pausing upstream is the only thing that stops the runner promoting it, and the reason rides on
        // that same call so tenants-service can emit one campaign.paused that explains itself.
        Mockito.verify(tenantsClient).transitionCampaign(Mockito.eq(tenantId), Mockito.eq(apiKeyId),
                Mockito.eq(campaignId.toString()), Mockito.eq("pause"),
                Mockito.eq(Map.of("reason", "QUOTA")));
    }

    @Test
    @DisplayName("this service writes no campaign.paused of its own — upstream emits the single event")
    void sweepEmitsNoEventItself() {
        createPlan("CAMPAIGN_TINY_EVENT", 1000, 10, 1000, "BLOCK", 3);
        assignPlan("CAMPAIGN_TINY_EVENT", null);
        issueLiveKey();
        UUID apiKeyId = apiKeyRepository.findAll().get(0).getId();

        Mockito.when(tenantsClient.listCampaigns(Mockito.eq(tenantId), Mockito.any(), Mockito.any()))
                .thenReturn(page(UUID.randomUUID(), apiKeyId,
                        Instant.now().plus(1, ChronoUnit.MINUTES), 50));

        scheduledQuotaJob.sweep();

        // Emitting one here as well gave a partner two events for one transition, the first of them
        // claiming MANUAL.
        assertThat(outboxEventRepository.findAll())
                .noneMatch(event -> WebhookEvents.CAMPAIGN_PAUSED.equals(event.getEventType()));
    }

    @Test
    @DisplayName("a campaign created in our UI is not the partner's allowance to spend")
    void sweepIgnoresUiCampaigns() {
        issueLiveKey();
        Mockito.when(tenantsClient.listCampaigns(Mockito.eq(tenantId), Mockito.any(), Mockito.any()))
                .thenReturn(page(UUID.randomUUID(), null, Instant.now().plus(1, ChronoUnit.MINUTES),
                        200));

        assertThat(scheduledQuotaJob.sweep()).isZero();
        assertThat(quotaService.peek(tenantId).today()).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    private JsonNode campaign(String status, int total, int suppressed, int frequencySkipped,
                              int invalid) {
        try {
            return objectMapper.readTree("""
                    {"id":"%s","name":"New listing","status":"%s","clientRef":"listing-4711",
                     "counts":{"total":%d,"queued":%d,"sent":0,"delivered":0,"read":0,"failed":0,
                               "suppressed":%d,"invalid":%d,"frequencySkipped":%d,"replied":0}}
                    """.formatted(UUID.randomUUID(), status, total, total, suppressed, invalid,
                    frequencySkipped));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode page(UUID campaignId, UUID apiKeyId, Instant scheduledAt, int total) {
        try {
            return objectMapper.readTree("""
                    {"content":[{"id":"%s","name":"Scheduled","status":"SCHEDULED",
                                 "apiKeyId":%s,"scheduledAt":"%s",
                                 "counts":{"total":%d,"suppressed":0,"invalid":0,
                                           "frequencySkipped":0}}]}
                    """.formatted(campaignId,
                    apiKeyId == null ? "null" : "\"" + apiKeyId + "\"",
                    scheduledAt.toString(), total));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
