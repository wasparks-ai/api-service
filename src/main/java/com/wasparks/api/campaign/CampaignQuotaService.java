package com.wasparks.api.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.quota.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Message quota for campaigns (§B3).
 *
 * <p>A campaign is the one thing on this API that spends a tenant's whole daily allowance in a single
 * request, so it is also the one place where "check the quota" has to mean something other than "reserve
 * one". Two questions decide the design, and the epic answers both.
 *
 * <h2>How many?</h2>
 * {@code recipientCount − suppressed − frequency_skipped}: the recipients that will actually be sent to.
 * Charging for a suppressed number would bill a partner for a message our own compliance rules forbid us
 * to send, and charging for a frequency-skipped one would bill it for a message the guard exists to
 * prevent. Both are computed upstream at create and reported in {@code counts}, so the number is read
 * off the response rather than recomputed — there is exactly one definition of who is sendable, and it
 * lives with the code that decides it.
 *
 * <p>{@code invalid} recipients are excluded too. They are not in the epic's formula because the epic
 * predates the status existing (amendment 5 added {@code counts.invalid} alongside
 * {@code frequencySkipped}), and a phone number that could not be parsed is as unsendable as a
 * suppressed one.
 *
 * <h2>When?</h2>
 * At <b>create</b> for a campaign that starts immediately, and at <b>start time</b> for a scheduled one.
 * A campaign created on Monday for Friday cannot reserve Friday's allowance on Monday — the counters are
 * per calendar day and Friday's does not exist yet. So a scheduled campaign passes create without
 * reserving, and {@link ScheduledCampaignQuotaJob} does it shortly before the runner picks it up.
 *
 * <p>The reservation is optimistic and the release is what makes it honest: if upstream then refuses the
 * create, the whole amount goes back. A partner whose campaign was rejected for a bad template must not
 * find its day's allowance gone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignQuotaService {

    private final QuotaService quotaService;

    /**
     * The sendable recipient count from a campaign body's {@code counts}, or 0 when it has none.
     *
     * <p>{@code total} rather than {@code queued}: {@code queued} is what the runner has left to do and
     * drops as the campaign progresses, while {@code total} is the size of the list. On a freshly created
     * campaign the two agree; on a running one only {@code total} still answers "how big was this?".
     */
    public int sendableCount(JsonNode campaign) {
        JsonNode counts = campaign == null ? null : campaign.get("counts");
        if (counts == null || !counts.isObject()) {
            // No counts means upstream told us nothing about the list. Reserving zero is the safe error:
            // the send path reserves per message anyway, so the worst case is a campaign that is
            // metered one message at a time rather than one that escapes metering.
            log.warn("Campaign response carried no counts — reserving no campaign quota");
            return 0;
        }
        int total = counts.path("total").asInt(0);
        int unsendable = counts.path("suppressed").asInt(0)
                + counts.path("frequencySkipped").asInt(0)
                + counts.path("invalid").asInt(0);
        return Math.max(0, total - unsendable);
    }

    /**
     * Reserve a campaign's worth of quota, or throw {@code 429 quota_exceeded}.
     *
     * <p>The rejection carries the campaign's own numbers in {@code details}, because "the day message
     * quota of 20000 has been used up" is not actionable on its own when the caller is trying to work out
     * whether to split a list or wait for tomorrow.
     */
    public void reserve(ApiPrincipal principal, int amount) {
        if (amount <= 0) {
            return;
        }
        QuotaService.Decision decision = quotaService.reserve(principal, amount);
        if (!decision.allowed()) {
            throw decision.toException().withDetail("requested", amount);
        }
    }

    /** Hand a reservation back after upstream refused the campaign it was taken for. */
    public void release(ApiPrincipal principal, int amount) {
        quotaService.release(principal, amount);
    }
}
