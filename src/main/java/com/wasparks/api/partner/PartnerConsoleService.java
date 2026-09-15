package com.wasparks.api.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.auth.ApiKeyService;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.PartnerPrincipal;
import com.wasparks.api.auth.TenantUserPrincipal;
import com.wasparks.api.entity.ApiKey;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.enums.ApiKeyStatus;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.AdminInternalClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.plans.PlanResolver;
import com.wasparks.api.quota.QuotaService;
import com.wasparks.api.repository.ApiKeyRepository;
import com.wasparks.api.repository.ApiPartnerTenantRepository;
import com.wasparks.api.repository.ApiUsageDailyRepository;
import com.wasparks.api.entity.ApiUsageDaily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The bridge between a tenant-web session and the partner services (§B6).
 *
 * <p>The Partner console performs exactly the operations {@code /v1/customers} performs, for exactly the
 * same actor — a partner — arriving with a different credential. So rather than a second implementation,
 * this turns a {@link TenantUserPrincipal} into the {@link ApiPrincipal} those services already take, and
 * the operations themselves are shared. Two implementations of "suspend a customer" would disagree about
 * cache invalidation or the cap rules within a release.
 *
 * <h2>The actor key</h2>
 * Both internal chains require {@code X-Actor-Api-Key} to be a real {@code api_keys} id, so a console
 * request needs a key to act as even though the human driving it has none. It reuses the UI-session key
 * mechanism (§D.3): a TEST-mode, one-hour, narrowly scoped row, minted on demand and remembered in Redis
 * for most of its life so a console session does not create one per click.
 *
 * <p>The audit trail upstream therefore reads {@code actor: "API_KEY"} with a short-lived key belonging to
 * the partner's own tenant — which is honest. It is not a user impersonation, and a key never becomes one
 * (epic §0.6): who pressed the button is recorded here, in this service's logs, not upstream.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartnerConsoleService {

    /** {@code console:actor:{tenantId}} — the id of the short-lived key console calls act as. */
    private static final String ACTOR_KEY_PREFIX = "console:actor:";
    /** Comfortably inside the one-hour key TTL, so a cached id is never handed out already expired. */
    private static final Duration ACTOR_CACHE_TTL = Duration.ofMinutes(45);

    private final AdminInternalClient adminClient;
    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiPartnerTenantRepository partnerTenantRepository;
    private final ApiUsageDailyRepository usageRepository;
    private final PlanResolver planResolver;
    private final QuotaService quotaService;
    private final StringRedisTemplate redis;

    /** The resolved partner plus the principal the shared services take. */
    public record Console(JsonNode partner, ApiPrincipal principal) {

        public UUID partnerId() {
            return principal.partner().partnerId();
        }
    }

    /**
     * Resolve the signed-in tenant to a partner, or {@code 404 not_a_partner}.
     *
     * <p>Asked of admin-service rather than of our own {@code api_partners} mapping (§B6): admin-service
     * owns what a partner is, SUSPENDED included, and deriving a second opinion from a table this service
     * only reads would be a second definition to keep in step.
     *
     * <p>404 rather than 403 because, to a tenant that is not a partner, this console does not exist —
     * tenant-web hides the whole section on the same signal, and a 403 would tell every tenant in the
     * estate that a partner programme is there to be asked about.
     */
    @Transactional
    public Console resolve(TenantUserPrincipal user) {
        UUID actorKeyId = actorKeyFor(user);

        JsonNode partner = upstream(() ->
                adminClient.findPartnerByOwnerTenant(user.tenantId(), actorKeyId))
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_A_PARTNER));

        UUID partnerId = uuid(partner, "id");
        if (partnerId == null) {
            log.error("admin-service returned a partner with no id for owner tenant {}",
                    user.tenantId());
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }

        EffectiveLimits limits = planResolver.resolve(user.tenantId());
        ApiPrincipal principal = new ApiPrincipal(actorKeyId, user.tenantId(), partnerId,
                ApiKeyMode.LIVE, Scope.allWire(), limits,
                new PartnerPrincipal(partnerId, user.tenantId(), user.tenantId(), null));
        return new Console(partner, principal);
    }

    /**
     * The key a console request acts as: a cached UI-session key, or a fresh one.
     *
     * <p>Re-validated on every use rather than trusted from the cache, because the purge job deletes these
     * rows once they expire and an id that no longer exists would make the upstream call a 401 the partner
     * could do nothing about.
     */
    private UUID actorKeyFor(TenantUserPrincipal user) {
        String cacheKey = ACTOR_KEY_PREFIX + user.tenantId();
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                UUID keyId = UUID.fromString(cached);
                if (apiKeyRepository.findByIdAndTenantId(keyId, user.tenantId())
                        .filter(key -> key.getStatus() == ApiKeyStatus.ACTIVE)
                        .filter(key -> key.isUsable(java.time.Instant.now()))
                        .isPresent()) {
                    return keyId;
                }
            }
        } catch (Exception e) {
            // A cache miss costs one extra key row per console session. Not worth failing over.
            log.debug("Console actor key cache unavailable: {}", e.getMessage());
        }

        ApiKey minted = apiKeyService.issueUiSessionKey(user.tenantId(), user.userId()).key();
        try {
            redis.opsForValue().set(cacheKey, minted.getId().toString(), ACTOR_CACHE_TTL);
        } catch (Exception e) {
            log.debug("Could not cache the console actor key: {}", e.getMessage());
        }
        return minted.getId();
    }

    // ------------------------------------------------------------------ plan, pool and usage

    /**
     * The partner's plan and pool meters (§B6).
     *
     * <p>{@code pool} comes from the live Redis counters, not from {@code api_usage_daily}: the table lags
     * by a flush interval, and a partner watching its pool while a campaign runs needs the number the next
     * send will be checked against rather than the one from ten minutes ago.
     *
     * <p>On a METERED plan the allowance is deliberately absent. The seeded metered plan's limits are set
     * absurdly high so they never bind, and rendering "11 of 1,000,000" as a meter would be noise dressed
     * as information — so a metered plan reports a count and a price, and a fixed one reports a meter.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> planAndPool(Console console) {
        EffectiveLimits limits = console.principal().limits();
        QuotaService.Usage pool = quotaService.peekPool(console.partnerId());

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("code", limits.planCode());
        plan.put("billingModel", limits.billingModel().name());
        plan.put("partnerPlan", limits.partnerPlan());
        plan.put("pricePerMessageMinor", limits.pricePerMessageMinor());
        plan.put("currency", limits.currency());
        plan.put("requestsPerMinute", limits.requestsPerMinute());
        plan.put("maxKeys", limits.maxKeys());
        plan.put("maxWebhookEndpoints", limits.maxWebhookEndpoints());
        if (limits.hasAllowance()) {
            plan.put("messagesPerDay", limits.messagesPerDay());
            plan.put("messagesPerMonth", limits.messagesPerMonth());
            plan.put("overagePolicy", limits.overagePolicy().name());
        }

        Map<String, Object> poolBody = new LinkedHashMap<>();
        poolBody.put("messagesToday", pool.today());
        poolBody.put("messagesThisMonth", pool.month());
        if (limits.hasAllowance()) {
            poolBody.put("remainingToday", Math.max(0, limits.messagesPerDay() - pool.today()));
            poolBody.put("remainingThisMonth", Math.max(0, limits.messagesPerMonth() - pool.month()));
        }
        if (limits.pricePerMessageMinor() != null) {
            // Indicative only. Invoicing is manual from admin-service's monthly export (§0.9); this is
            // the partner's own running estimate and is labelled as such in the console.
            poolBody.put("estimatedAmountMinor", pool.month() * limits.pricePerMessageMinor());
            poolBody.put("currency", limits.currency());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", plan);
        body.put("pool", poolBody);
        body.put("clientCount", partnerTenantRepository.countByPartnerId(console.partnerId()));
        return body;
    }

    /**
     * Usage per client for a month, from {@code api_usage_daily} (§B6).
     *
     * <p>Only the tenant-wide <b>sentinel</b> rows are summed ({@code api_key_id} all zeroes). Summing the
     * per-key rows as well would double every number, because a key's usage is already counted in its
     * tenant's total — the same trap admin-service's export documents, and worth repeating here because
     * the two numbers must agree when a partner compares this screen with its invoice.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> usage(Console console, LocalDate from, LocalDate to) {
        List<Map<String, Object>> clients = new ArrayList<>();
        long totalAccepted = 0;
        long totalRequests = 0;

        for (UUID tenantId : allTenantIds(console)) {
            long accepted = 0;
            long requests = 0;
            long rateLimited = 0;
            for (ApiUsageDaily row : usageRepository.findByTenantIdAndDayBetween(tenantId, from, to)) {
                if (!ApiUsageDaily.TENANT_TOTAL.equals(row.getApiKeyId())) {
                    continue;
                }
                accepted += row.getMessagesAccepted();
                requests += row.getRequests();
                rateLimited += row.getRateLimited();
            }
            Map<String, Object> client = new LinkedHashMap<>();
            client.put("customerId", tenantId.toString());
            client.put("ownerTenant", tenantId.equals(console.principal().partner().ownerTenantId()));
            client.put("messagesAccepted", accepted);
            client.put("requests", requests);
            client.put("rateLimited", rateLimited);
            clients.add(client);
            totalAccepted += accepted;
            totalRequests += requests;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from.toString());
        body.put("to", to.toString());
        body.put("totals", Map.of("messagesAccepted", totalAccepted, "requests", totalRequests));
        body.put("clients", clients);
        return body;
    }

    /**
     * Every tenant whose usage counts against this partner: its clients <b>and its own tenant</b>.
     *
     * <p>The partner's own sends draw on the same pool — it is a tenant like any other and its number
     * works as it always did — so an export that left it out would not add up to the invoice.
     */
    private List<UUID> allTenantIds(Console console) {
        List<UUID> tenantIds = new ArrayList<>();
        tenantIds.add(console.principal().partner().ownerTenantId());
        partnerTenantRepository.findByPartnerIdOrderByCreatedAtDesc(console.partnerId())
                .forEach(link -> {
                    if (!tenantIds.contains(link.getTenantId())) {
                        tenantIds.add(link.getTenantId());
                    }
                });
        return tenantIds;
    }

    private UUID uuid(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        try {
            return UUID.fromString(node.get(field).asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private <T> Optional<T> upstream(java.util.function.Supplier<Optional<T>> call) {
        try {
            return call.get();
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            log.warn("admin-service unavailable resolving a partner: {}", unavailable.getMessage());
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE,
                    "The partner console is temporarily unavailable. Retry shortly.");
        }
    }
}
