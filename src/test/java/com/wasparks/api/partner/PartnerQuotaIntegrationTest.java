package com.wasparks.api.partner;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.PartnerPrincipal;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.plans.PlanResolver;
import com.wasparks.api.quota.QuotaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pool versus cap (§0.9, §B1): a partner key must satisfy <b>both</b> its client's daily ceiling and the
 * partner plan's shared allowance, and a refusal from either must leave no counter advanced.
 *
 * <p>The rollback cases are the ones worth having. A reservation that takes the client's cap and is then
 * refused by the pool would silently burn that customer's day for a message nobody sent — invisible in
 * every log, and visible to the customer only as messages that stop going out an hour early.
 */
class PartnerQuotaIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private QuotaService quotaService;
    @Autowired
    private PlanResolver planResolver;
    @Autowired
    private PartnerTenantResolver resolver;
    @Autowired
    private PartnerCustomerService customerService;

    @Test
    @DisplayName("the client cap binds before the pool does")
    void clientCapBinds() {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", 2);
        ApiPrincipal principal = partnerPrincipal(partnerId, clientId, 2, 1000, 100000);

        assertThat(quotaService.reserve(principal).allowed()).isTrue();
        assertThat(quotaService.reserve(principal).allowed()).isTrue();

        QuotaService.Decision third = quotaService.reserve(principal);
        assertThat(third.allowed()).isFalse();
        assertThat(third.scope()).isEqualTo("client");
        assertThat(third.limit()).isEqualTo(2);
    }

    @Test
    @DisplayName("the pool binds when the client is uncapped")
    void poolBinds() {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        ApiPrincipal principal = partnerPrincipal(partnerId, clientId, null, 1, 100000);

        assertThat(quotaService.reserve(principal).allowed()).isTrue();

        QuotaService.Decision second = quotaService.reserve(principal);
        assertThat(second.allowed()).isFalse();
        assertThat(second.scope()).isEqualTo("day");
    }

    @Test
    @DisplayName("two clients of one partner share the pool")
    void poolIsShared() {
        UUID partnerId = makePartner("leadboard");
        UUID first = addClient(partnerId, "cust_1", null);
        UUID second = addClient(partnerId, "cust_2", null);

        // A pool of two: one each, and the third request from either is refused. This is the whole point
        // of a partner plan — the allowance is the partner's, not per customer.
        assertThat(quotaService.reserve(partnerPrincipal(partnerId, first, null, 2, 100)).allowed())
                .isTrue();
        assertThat(quotaService.reserve(partnerPrincipal(partnerId, second, null, 2, 100)).allowed())
                .isTrue();
        assertThat(quotaService.reserve(partnerPrincipal(partnerId, first, null, 2, 100)).allowed())
                .isFalse();
    }

    @Test
    @DisplayName("a cap larger than the pool never raises anything")
    void capCannotExceedThePool() {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", 10_000);
        ApiPrincipal principal = partnerPrincipal(partnerId, clientId, 10_000, 1, 100000);

        assertThat(quotaService.reserve(principal).allowed()).isTrue();
        assertThat(quotaService.reserve(principal).allowed()).isFalse();
    }

    @Test
    @DisplayName("a pool refusal hands the client's cap slot back")
    void poolRefusalReleasesTheClientCap() {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", 5);
        ApiPrincipal principal = partnerPrincipal(partnerId, clientId, 5, 1, 100000);

        assertThat(quotaService.reserve(principal).allowed()).isTrue();
        assertThat(quotaService.reserve(principal).allowed()).isFalse();

        // One message was accepted, so exactly one of the client's five may be spent. If the refused
        // reservation had stuck, this would read 2 and the customer would lose a slot per rejection.
        assertThat(quotaService.peek(clientId).today()).isEqualTo(1);
    }

    @Test
    @DisplayName("a month refusal hands the day and the client's cap back")
    void monthRefusalRollsEverythingBack() {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", 50);
        ApiPrincipal principal = partnerPrincipal(partnerId, clientId, 50, 1000, 0);

        assertThat(quotaService.reserve(principal).allowed()).isFalse();
        assertThat(quotaService.peek(clientId).today()).isZero();
        assertThat(quotaService.peekPool(partnerId).today()).isZero();
    }

    @Test
    @DisplayName("a bulk reservation is all or nothing")
    void bulkIsAtomic() {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        ApiPrincipal principal = partnerPrincipal(partnerId, clientId, null, 100, 100000);

        // A campaign of 150 against a pool of 100 must take none of it. Taking 100 and refusing would
        // leave the partner unable to send anything else for a campaign that never ran.
        assertThat(quotaService.reserve(principal, 150).allowed()).isFalse();
        assertThat(quotaService.peekPool(partnerId).today()).isZero();

        assertThat(quotaService.reserve(principal, 100).allowed()).isTrue();
        assertThat(quotaService.peekPool(partnerId).today()).isEqualTo(100);
    }

    @Test
    @DisplayName("release gives back the pool and the cap together")
    void releaseUndoesBoth() {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", 100);
        ApiPrincipal principal = partnerPrincipal(partnerId, clientId, 100, 1000, 100000);

        quotaService.reserve(principal, 40);
        quotaService.release(principal, 40);

        assertThat(quotaService.peek(clientId).today()).isZero();
        assertThat(quotaService.peekPool(partnerId).today()).isZero();
    }

    // ------------------------------------------------------------------ client keys

    @Test
    @DisplayName("a client key draws on its partner's pool, not on the default plan")
    void clientKeyPools() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        assignPlanTo(tenantId, "PARTNER_STARTER");
        String clientKey = apiKeyService.issueForTenant(clientId, null, "client key",
                ApiKeyMode.LIVE, null, null).plaintext();

        // The client tenant has no plan of its own, so before this it ran on the seeded FREE plan —
        // 200 messages a day for a customer whose partner had 20,000 unspent.
        ApiPrincipal resolved = resolver.resolve(apiKeyService.resolve(clientKey), null);

        assertThat(resolved.isPartner()).isTrue();
        assertThat(resolved.partner().partnerId()).isEqualTo(partnerId);
        assertThat(resolved.partner().ownerTenantId()).isEqualTo(tenantId);
        assertThat(resolved.partner().actingTenantId()).isEqualTo(clientId);
        assertThat(resolved.limits().planCode()).isEqualTo("PARTNER_STARTER");

        // And it spends the pool, exactly as the X-Tenant-Id path does.
        assertThat(quotaService.reserve(resolved, 5).allowed()).isTrue();
        assertThat(quotaService.peekPool(partnerId).today()).isEqualTo(5);
    }

    @Test
    @DisplayName("a client key is still bound by the cap its partner set")
    void clientKeyRespectsTheCap() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", 3);
        assignPlanTo(tenantId, "PARTNER_STARTER");
        String clientKey = apiKeyService.issueForTenant(clientId, null, "client key",
                ApiKeyMode.LIVE, null, null).plaintext();

        ApiPrincipal resolved = resolver.resolve(apiKeyService.resolve(clientKey), null);
        assertThat(resolved.partner().clientDailyCap()).isEqualTo(3);

        QuotaService.Decision decision = quotaService.reserve(resolved, 4);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.scope()).isEqualTo("client");
    }

    @Test
    @DisplayName("a client tenant with an explicitly assigned plan keeps its own plan")
    void explicitPlanWins() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        assignPlanTo(tenantId, "PARTNER_STARTER");
        // An admin has deliberately put this customer on a plan. That is a decision about this tenant,
        // and pooling it would silently overrule the admin who made it.
        assignPlanTo(clientId, "BUSINESS");

        String clientKey = apiKeyService.issueForTenant(clientId, null, "client key",
                ApiKeyMode.LIVE, null, null).plaintext();
        ApiPrincipal resolved = resolver.resolve(apiKeyService.resolve(clientKey), null);

        assertThat(resolved.isPartner()).isFalse();
        assertThat(resolved.limits().planCode()).isEqualTo("BUSINESS");

        quotaService.reserve(resolved, 5);
        assertThat(quotaService.peek(clientId).today()).isEqualTo(5);
        assertThat(quotaService.peekPool(partnerId).today()).isZero();
    }

    @Test
    @DisplayName("a client key for a suspended customer is refused like any other credential")
    void clientKeyOfSuspendedCustomer() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null, "SUSPENDED");
        String clientKey = apiKeyService.issueForTenant(clientId, null, "client key",
                ApiKeyMode.LIVE, null, null).plaintext();

        ApiPrincipal base = apiKeyService.resolve(clientKey);
        assertThatThrownBy(() -> resolver.resolve(base, null))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
                        .isEqualTo(ApiErrorCode.CUSTOMER_SUSPENDED));
    }

    @Test
    @DisplayName("a key on a tenant that is nobody's customer is left exactly as it was")
    void ordinaryTenantKeyIsUntouched() throws Exception {
        String key = issueLiveKey();
        ApiPrincipal resolved = resolver.resolve(apiKeyService.resolve(key), null);

        assertThat(resolved.isPartner()).isFalse();
        assertThat(resolved.tenantId()).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("a cap change invalidates the client-key context, not just the partner hash")
    void capChangeInvalidatesTheClientContext() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", 3);
        assignPlanTo(tenantId, "PARTNER_STARTER");
        String clientKey = apiKeyService.issueForTenant(clientId, null, "client key",
                ApiKeyMode.LIVE, null, null).plaintext();

        assertThat(resolver.resolve(apiKeyService.resolve(clientKey), null)
                .partner().clientDailyCap()).isEqualTo(3);

        customerService.update(partnerPrincipalForOwner(partnerId), clientId,
                new PartnerCustomerService.UpdateRequest(null, 50, null, null));

        // Without invalidateClient this would still read 3 for the rest of the minute, and the console
        // would be showing a cap the customer's own key was not getting.
        assertThat(resolver.resolve(apiKeyService.resolve(clientKey), null)
                .partner().clientDailyCap()).isEqualTo(50);
    }

    @Test
    @DisplayName("an ordinary tenant key still uses the P1 tenant counters")
    void tenantKeysAreUnchanged() {
        ApiPrincipal principal = new ApiPrincipal(UUID.randomUUID(), tenantId, null, ApiKeyMode.LIVE,
                Set.of(), limits(1, 100000), null);

        assertThat(quotaService.reserve(principal).allowed()).isTrue();
        assertThat(quotaService.reserve(principal).allowed()).isFalse();
        assertThat(quotaService.peek(tenantId).today()).isEqualTo(1);
    }

    @Test
    @DisplayName("the resolved plan carries the billing model through")
    void planCarriesBillingModel() {
        assignPlanTo(tenantId, "PARTNER_METERED");
        EffectiveLimits limits = planResolver.resolve(tenantId);

        assertThat(limits.partnerPlan()).isTrue();
        assertThat(limits.hasAllowance()).isFalse();
        // Amendment 13: 021 seeds the price in place, so a metered partner can be invoiced from day one.
        assertThat(limits.pricePerMessageMinor()).isEqualTo(50L);
        assertThat(limits.currency()).isEqualTo("INR");
    }

    // ------------------------------------------------------------------ fixtures

    /** A partner principal acting on its own tenant — what the console's writes run as. */
    private ApiPrincipal partnerPrincipalForOwner(UUID partnerId) {
        return new ApiPrincipal(UUID.randomUUID(), tenantId, partnerId, ApiKeyMode.LIVE, Set.of(),
                limits(1000, 100000), new PartnerPrincipal(partnerId, tenantId, tenantId, null));
    }

    private ApiPrincipal partnerPrincipal(UUID partnerId, UUID clientId, Integer cap,
                                          int messagesPerDay, int messagesPerMonth) {
        return new ApiPrincipal(UUID.randomUUID(), clientId, partnerId, ApiKeyMode.LIVE, Set.of(),
                limits(messagesPerDay, messagesPerMonth),
                new PartnerPrincipal(partnerId, tenantId, clientId, cap));
    }

    private EffectiveLimits limits(int messagesPerDay, int messagesPerMonth) {
        return new EffectiveLimits("TEST", 1000, messagesPerDay, messagesPerMonth, 100, 10, 10,
                com.wasparks.api.enums.OveragePolicy.BLOCK, false,
                com.wasparks.api.enums.BillingModel.FIXED, null, null, true);
    }
}
