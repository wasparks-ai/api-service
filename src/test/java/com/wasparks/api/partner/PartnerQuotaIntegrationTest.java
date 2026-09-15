package com.wasparks.api.partner;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.PartnerPrincipal;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.plans.PlanResolver;
import com.wasparks.api.quota.QuotaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
