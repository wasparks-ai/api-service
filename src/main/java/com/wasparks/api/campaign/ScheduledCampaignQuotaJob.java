package com.wasparks.api.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.PartnerPrincipal;
import com.wasparks.api.entity.ApiPartner;
import com.wasparks.api.entity.ApiPartnerTenant;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.plans.PlanResolver;
import com.wasparks.api.quota.QuotaService;
import com.wasparks.api.repository.ApiKeyRepository;
import com.wasparks.api.repository.ApiPartnerRepository;
import com.wasparks.api.repository.ApiPartnerTenantRepository;
import com.wasparks.api.webhook.WebhookEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reserves quota for campaigns that are about to start (§B3).
 *
 * <h2>Why this exists at all</h2>
 * A campaign created today for next Tuesday cannot reserve Tuesday's allowance today: the counters are
 * per calendar day and Tuesday's does not exist yet. Reserving at create would either charge the wrong
 * day or require a forward-dated ledger this service does not have. So a SCHEDULED campaign passes
 * create without reserving, and this job takes the allowance a few minutes before the runner promotes it.
 *
 * <p>The window matters in both directions. Too early and the reservation lands on the wrong day for a
 * campaign scheduled just after midnight; too late and the runner has already started sending. Five
 * minutes ahead, checked every minute, is the epic's number and sits comfortably inside both.
 *
 * <h2>When the allowance is gone</h2>
 * The campaign is paused upstream — the only way to stop the runner picking it up — and a
 * {@code campaign.paused} with reason {@code QUOTA} is emitted, which is a fourth reason the picker and
 * the docs now carry. It is not cancelled: an allowance that ran out today is usually there tomorrow,
 * and a partner that has to recreate a 4,000-recipient campaign because we cancelled it has been
 * punished for our scheduling rather than for its own.
 *
 * <p><b>ShedLock, not idempotence.</b> Reserving twice would charge a campaign twice, so this job must
 * run on one replica — and, because a reservation leaves no mark on the campaign row, there is nothing to
 * make it idempotent with. The lock is the mechanism; {@link #reserved} is a second, cheaper guard
 * against the same tick being repeated within the window on one replica.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledCampaignQuotaJob {

    /** Campaigns whose quota this replica has already taken, so a repeated tick cannot double-charge. */
    private final Set<UUID> reserved = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Bounded, because a long-lived process would otherwise remember every campaign it ever started. */
    private static final int REMEMBERED_CAMPAIGNS = 10_000;

    private final InternalTenantsClient tenantsClient;
    private final CampaignQuotaService campaignQuota;
    private final QuotaService quotaService;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiPartnerRepository partnerRepository;
    private final ApiPartnerTenantRepository partnerTenantRepository;
    private final PlanResolver planResolver;
    private final WebhookEventPublisher eventPublisher;

    @Value("${app.partner.scheduled-quota.lookahead-minutes}")
    private int lookaheadMinutes;

    @Scheduled(fixedDelayString = "${app.partner.scheduled-quota.poll-ms}", initialDelay = 30000)
    @SchedulerLock(name = "api-scheduled-campaign-quota", lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT1S")
    public void poll() {
        try {
            sweep();
        } catch (Exception e) {
            log.error("Scheduled campaign quota sweep failed", e);
        }
    }

    /** Visible for tests: one sweep, returning how many campaigns were reserved for. */
    public int sweep() {
        Instant cutoff = Instant.now().plus(Duration.ofMinutes(lookaheadMinutes));
        int handled = 0;

        // Only tenants that hold an API key can have an API campaign, and only API campaigns are
        // metered here — a campaign a human scheduled in our own UI is not the partner's allowance to
        // spend. That narrows the sweep from "every tenant" to a list that is short by construction.
        for (ApiKeyRepository.TenantKeyRef tenant : apiKeyRepository.findActiveTenantKeys()) {
            handled += sweepTenant(tenant.getTenantId(), tenant.getApiKeyId(), cutoff);
        }
        if (reserved.size() > REMEMBERED_CAMPAIGNS) {
            // The ids are only a guard against a repeated tick; once a campaign has started, forgetting
            // it is harmless — its status is no longer SCHEDULED, so it will never be seen again.
            reserved.clear();
        }
        return handled;
    }

    private int sweepTenant(UUID tenantId, UUID readerKeyId, Instant cutoff) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("status", "SCHEDULED");
        query.add("size", "100");

        JsonNode page;
        try {
            // The internal chain requires a real api_keys id as the actor, so the sweep reads as one
            // of the tenant's own keys rather than as nobody.
            page = tenantsClient.listCampaigns(tenantId, readerKeyId, query);
        } catch (Exception e) {
            // One tenant's failure must not stop the sweep: the campaign that could not be read is the
            // one that will start unreserved, and every other tenant still gets its chance.
            log.warn("Could not list scheduled campaigns for tenant {}: {}", tenantId, e.getMessage());
            return 0;
        }

        int handled = 0;
        for (JsonNode campaign : campaigns(page)) {
            if (isDue(campaign, cutoff) && reserveFor(tenantId, campaign)) {
                handled++;
            }
        }
        return handled;
    }

    /**
     * Reserve for one campaign, pausing it if the allowance is gone.
     *
     * @return true when this call took a reservation or paused the campaign
     */
    private boolean reserveFor(UUID tenantId, JsonNode campaign) {
        UUID campaignId = uuid(campaign, "id");
        UUID apiKeyId = uuid(campaign, "apiKeyId");
        if (campaignId == null || apiKeyId == null) {
            // No api_key_id means it was created in our UI, not over this API. Not ours to meter.
            return false;
        }
        if (!reserved.add(campaignId)) {
            return false;
        }

        ApiPrincipal principal = principalFor(tenantId, apiKeyId);
        int amount = campaignQuota.sendableCount(campaign);
        if (amount <= 0) {
            return false;
        }

        QuotaService.Decision decision = quotaService.reserve(principal, amount);
        if (decision.allowed()) {
            log.debug("Reserved {} messages for scheduled campaign {}", amount, campaignId);
            return true;
        }

        // Out of allowance. Forget the id first: the campaign will still be SCHEDULED if the pause
        // below fails, and the next tick should try again rather than skip it forever.
        reserved.remove(campaignId);
        pauseForQuota(tenantId, apiKeyId, campaignId, campaign, decision, amount);
        return true;
    }

    private void pauseForQuota(UUID tenantId, UUID apiKeyId, UUID campaignId, JsonNode campaign,
                               QuotaService.Decision decision, int amount) {
        log.info("Campaign {} cannot start: {} quota of {} exhausted, {} messages needed",
                campaignId, decision.scope(), decision.limit(), amount);
        try {
            tenantsClient.transitionCampaign(tenantId, apiKeyId, campaignId.toString(), "pause");
        } catch (Exception e) {
            // The runner will start it anyway and the sends will be metered one at a time, which is a
            // worse outcome than a pause but not a wrong one. Logged at warn because it means a partner
            // is about to go over its pool.
            log.warn("Could not pause over-quota campaign {}: {}", campaignId, e.getMessage());
        }
        eventPublisher.publishCampaignQuotaPaused(tenantId, campaignId, summary(campaign),
                decision.scope(), decision.limit(), amount);
    }

    /**
     * Rebuild the principal a campaign was created under, well enough to charge the right counters.
     *
     * <p>Only the quota-relevant parts are real: the tenant, the partner context and the plan. Scopes are
     * empty and the mode is TEST because nothing here sends or authorises anything — a principal that
     * carried send scopes would be a credential this job had no business minting. The job never passes it
     * to anything but {@link QuotaService}.
     */
    private ApiPrincipal principalFor(UUID tenantId, UUID apiKeyId) {
        Optional<ApiPartnerTenant> link = partnerTenantRepository.findPartnerIdsByTenantId(tenantId)
                .stream()
                .findFirst()
                .flatMap(partnerId -> partnerTenantRepository.findByPartnerIdAndTenantId(
                        partnerId, tenantId));

        if (link.isEmpty()) {
            return new ApiPrincipal(apiKeyId, tenantId, null, ApiKeyMode.TEST, Set.of(),
                    planResolver.resolve(tenantId), null);
        }

        ApiPartnerTenant row = link.get();
        UUID ownerTenantId = partnerRepository.findById(row.getPartnerId())
                .map(ApiPartner::getOwnerTenantId)
                .orElse(tenantId);
        return new ApiPrincipal(apiKeyId, tenantId, row.getPartnerId(), ApiKeyMode.TEST, Set.of(),
                // The pool plan lives on the partner's own tenant (§B1), not on the client's.
                planResolver.resolve(ownerTenantId),
                new PartnerPrincipal(row.getPartnerId(), ownerTenantId, tenantId,
                        row.getMessagesPerDayCap()));
    }

    /** The campaign fields worth putting on the event — not the whole body, which can be large. */
    private Map<String, Object> summary(JsonNode campaign) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("campaignId", text(campaign, "id"));
        summary.put("name", text(campaign, "name"));
        summary.put("clientRef", text(campaign, "clientRef"));
        summary.put("scheduledAt", text(campaign, "scheduledAt"));
        if (campaign.has("counts")) {
            summary.put("counts", campaign.get("counts"));
        }
        return summary;
    }

    /**
     * Upstream's paged list is {@code {content:[…]}} or a bare array depending on the endpoint; both are
     * accepted rather than asserting one, because a list shape is the kind of thing that changes without
     * anyone thinking of it as a contract change.
     */
    private Iterable<JsonNode> campaigns(JsonNode page) {
        if (page == null) {
            return java.util.List.of();
        }
        if (page.isArray()) {
            return page;
        }
        for (String field : java.util.List.of("content", "data", "campaigns")) {
            JsonNode list = page.get(field);
            if (list != null && list.isArray()) {
                return list;
            }
        }
        return java.util.List.of();
    }

    private boolean isDue(JsonNode campaign, Instant cutoff) {
        String scheduledAt = text(campaign, "scheduledAt");
        if (scheduledAt == null) {
            return false;
        }
        try {
            return !OffsetDateTime.parse(scheduledAt).toInstant().isAfter(cutoff);
        } catch (DateTimeParseException e) {
            try {
                return !Instant.parse(scheduledAt).isAfter(cutoff);
            } catch (DateTimeParseException ignored) {
                log.warn("Campaign {} has an unparseable scheduledAt: {}", text(campaign, "id"),
                        scheduledAt);
                return false;
            }
        }
    }

    private UUID uuid(JsonNode node, String field) {
        String raw = text(node, field);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
