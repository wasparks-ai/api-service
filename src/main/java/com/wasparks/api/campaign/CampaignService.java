package com.wasparks.api.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Campaigns over the API (§B3): validation, quota, and a thin proxy over the campaign engine.
 *
 * <p><b>There is no second sender here.</b> A campaign created through this API is an ordinary
 * {@code campaigns} row run by the ordinary runner, so tier caps, warm-up, quality auto-pause,
 * suppression and the {@code campaigns.runner.live-send-enabled} gate all apply unchanged (§0.8). What
 * this layer adds is exactly four things, and it is worth naming them because everything else is
 * pass-through:
 *
 * <ol>
 *   <li><b>Meta-style validation of the template block</b>, so a malformed campaign is refused with the
 *       same vocabulary a malformed send is, rather than as an upstream 400 the partner has to guess at;
 *   <li><b>the inline recipient cap</b> (5,000 per request), applied before the body crosses the wire;
 *   <li><b>quota reservation</b> — the whole sendable list at once, at create or at start;
 *   <li><b>{@code clientRef} echo</b>, which is what lets a partner match a campaign to a row in its own
 *       database without storing our id.
 * </ol>
 *
 * <h2>respectQuietHours is reserved</h2>
 * It is accepted, forwarded and audited upstream, and <b>enforces nothing</b> (amendment 2): campaign
 * sending has no quiet-hours engine — quiet hours are a reminders concept. It exists so that a partner
 * forwarding §B3's body verbatim does not get a 400, and so that the field name is reserved rather than
 * being claimed later by something with different semantics. Building it is a separate epic, and the
 * documentation says so in as many words rather than implying a guarantee we do not make.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignService {

    /** The four transitions upstream exposes (§B3). Anything else is a 404 before it leaves here. */
    private static final List<String> TRANSITIONS = List.of("start", "pause", "resume", "cancel");

    /** The template header types Meta accepts on a campaign. Matches the composer's own vocabulary. */
    private static final List<String> HEADER_TYPES = List.of("image", "video", "document", "text");

    private final InternalTenantsClient tenantsClient;
    private final CampaignQuotaService campaignQuota;

    @Value("${app.partner.max-inline-recipients}")
    private int maxInlineRecipients;

    /**
     * Create a campaign, reserving quota for it when it starts now.
     *
     * <p>The order is: validate locally, create upstream, <em>then</em> reserve. Reserving first would
     * mean a campaign refused for an unapproved template still cost the partner its allowance for the
     * few milliseconds until the release — and, if this process died in between, permanently. Creating
     * first also means the reservation is computed from upstream's own {@code counts}, which is the only
     * place that knows how many recipients survived suppression and the frequency guard.
     *
     * <p>A <b>scheduled</b> campaign reserves nothing here. Its allowance belongs to a day whose counter
     * does not exist yet; {@link ScheduledCampaignQuotaJob} takes it shortly before the runner does.
     */
    public JsonNode create(ApiPrincipal principal, JsonNode body) {
        validateCreate(body);

        JsonNode campaign = proxy(() -> tenantsClient.createCampaign(principal, body));

        if (startsImmediately(campaign)) {
            reserveOrUnwind(principal, campaign, "create");
        }
        return campaign;
    }

    /**
     * Start a DRAFT campaign, reserving its quota first.
     *
     * <p>Here the reservation comes <em>before</em> the transition, which is the opposite of create and
     * for the opposite reason: at create the campaign does not exist yet and cannot be un-created, while
     * at start it does exist and can simply stay a draft. A partner over its quota gets a 429 and a
     * campaign it can start tomorrow, rather than a running campaign that dies halfway.
     */
    public JsonNode start(ApiPrincipal principal, String id) {
        JsonNode campaign = proxy(() -> tenantsClient.getCampaign(
                principal.tenantId(), principal.keyId(), id));
        int amount = campaignQuota.sendableCount(campaign);
        campaignQuota.reserve(principal, amount);

        try {
            return proxy(() -> tenantsClient.transitionCampaign(
                    principal.tenantId(), principal.keyId(), id, "start"));
        } catch (RuntimeException e) {
            // Upstream refused the transition — wrong state, unapproved template. Nothing will be sent,
            // so the reservation goes back.
            campaignQuota.release(principal, amount);
            throw e;
        }
    }

    /** {@code pause} | {@code resume} | {@code cancel}. {@code start} goes through {@link #start}. */
    public JsonNode transition(ApiPrincipal principal, String id, String transition) {
        if (!TRANSITIONS.contains(transition)) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "No such campaign action.");
        }
        if ("start".equals(transition)) {
            return start(principal, id);
        }
        // Pause, resume and cancel do not touch the reservation. A paused campaign is expected to resume
        // and would have to re-reserve against a possibly different day; a cancelled one has usually sent
        // some of its list already, and refunding the unsent remainder would mean tracking how far the
        // runner got — a reconciliation, not a release. The nightly rollup from `messages` is where that
        // belongs, and it is P1.1 (hand-off §8) in both epics.
        return proxy(() -> tenantsClient.transitionCampaign(
                principal.tenantId(), principal.keyId(), id, transition));
    }

    /**
     * Append recipients to a DRAFT or SCHEDULED campaign.
     *
     * <p>No quota is reserved: a DRAFT has not reserved anything yet and will at start, and a SCHEDULED
     * one is reserved by the poller from the counts as they stand at that moment. Reserving here as well
     * would double-charge the same recipients.
     */
    public JsonNode appendRecipients(ApiPrincipal principal, String id, JsonNode body) {
        requireInlineLimit(body, "recipients");
        return proxy(() -> tenantsClient.appendCampaignRecipients(principal, id, body));
    }

    // ------------------------------------------------------------------ validation

    /**
     * The checks worth making before the body crosses the wire.
     *
     * <p>Not a re-implementation of upstream's validation — that runs too, and it is authoritative. These
     * are the ones whose failure upstream would report in its own vocabulary and whose cause is entirely
     * visible from the body: a missing template name, a header media block with no link, a recipient list
     * past the cap. Catching them here means the partner gets our error codes and our field names for the
     * mistakes it can fix by reading its own request.
     */
    void validateCreate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "A campaign body is required.");
        }
        requireText(body, "name");

        JsonNode template = body.get("template");
        if (template == null || !template.isObject()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`template` is required — a campaign always sends a template.");
        }
        requireText(template, "template.name");
        requireText(template, "template.language");

        JsonNode headerMedia = template.get("headerMedia");
        if (headerMedia != null && !headerMedia.isNull()) {
            if (!headerMedia.isObject() || !headerMedia.hasNonNull("link")) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "`template.headerMedia` must carry a `link` to the media.");
            }
            JsonNode type = headerMedia.get("type");
            if (type != null && type.isTextual()
                    && !HEADER_TYPES.contains(type.asText().toLowerCase())) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                                "`template.headerMedia.type` must be one of " + HEADER_TYPES + ".")
                        .withDetail("allowed", HEADER_TYPES);
            }
        }

        JsonNode buttons = template.get("buttonParams");
        if (buttons != null && !buttons.isNull()) {
            if (!buttons.isArray()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "`template.buttonParams` must be an array.");
            }
            for (JsonNode button : buttons) {
                if (!button.hasNonNull("index") || !button.get("index").isInt()) {
                    throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                            "Each `template.buttonParams` entry needs an integer `index`.");
                }
                if (!button.hasNonNull("value")) {
                    throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                            "Each `template.buttonParams` entry needs a `value`.");
                }
            }
        }

        validateAudience(body.get("audience"));
        requireClientRefLength(body);
    }

    /**
     * Exactly one audience source (internal.md: two or zero is a 400 upstream).
     *
     * <p>Checked here as well because the message can be so much better: upstream can only say
     * "invalid_request", while here we know which of the three the caller sent and can name them.
     */
    private void validateAudience(JsonNode audience) {
        if (audience == null || !audience.isObject()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`audience` is required — send `recipients`, `audienceId` or `csvUploadId`.");
        }
        int sources = 0;
        if (audience.hasNonNull("recipients")) {
            sources++;
        }
        if (audience.hasNonNull("audienceId")) {
            sources++;
        }
        if (audience.hasNonNull("csvUploadId")) {
            sources++;
        }
        if (sources != 1) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                            "`audience` takes exactly one of `recipients`, `audienceId` or "
                                    + "`csvUploadId`.")
                    .withDetail("sourcesGiven", sources);
        }
        requireInlineLimit(audience, "recipients");
    }

    /**
     * The inline cap (§B3, open fork: 5,000 per request is the default).
     *
     * <p>It is a cap on one <em>request</em>, not on a campaign: {@code POST /{id}/recipients} appends
     * more. The reason for a limit at all is that the whole list is parsed, normalised, deduplicated and
     * suppression-checked synchronously upstream while the partner's HTTP request waits, and a list of a
     * million would take longer than any sensible client timeout.
     */
    public void requireInlineLimit(JsonNode node, String field) {
        JsonNode list = node == null ? null : node.get(field);
        if (list == null || !list.isArray()) {
            return;
        }
        if (list.size() > maxInlineRecipients) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                            "`" + field + "` is limited to " + maxInlineRecipients
                                    + " per request. Create the campaign with the first batch and "
                                    + "append the rest.")
                    .withDetail("limit", maxInlineRecipients)
                    .withDetail("given", list.size());
        }
    }

    /** {@code campaigns.client_ref} is VARCHAR(64); a longer one would be truncated or rejected late. */
    private void requireClientRefLength(JsonNode body) {
        JsonNode clientRef = body.get("clientRef");
        if (clientRef != null && clientRef.isTextual() && clientRef.asText().length() > 64) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`clientRef` is limited to 64 characters.");
        }
    }

    private void requireText(JsonNode node, String path) {
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "`" + path + "` is required.");
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A campaign that left the create as DRAFT starts when the partner says so; one that left SCHEDULED
     * has a {@code startAt} the runner is already watching. Only the first reserves now.
     */
    private boolean startsImmediately(JsonNode campaign) {
        String status = campaign != null && campaign.hasNonNull("status")
                ? campaign.get("status").asText() : "";
        return !"SCHEDULED".equals(status);
    }

    /**
     * Reserve for a campaign that already exists upstream, cancelling it if the quota refuses.
     *
     * <p>This is the awkward corner of create-then-reserve, and it is worth being explicit about the
     * trade. The campaign exists by the time we know its size, so an over-quota campaign has to be
     * undone rather than never made. Cancelling it is better than leaving it: a DRAFT nobody can start
     * is a support ticket, while a CANCELLED campaign carrying a 429 explains itself.
     */
    private void reserveOrUnwind(ApiPrincipal principal, JsonNode campaign, String phase) {
        int amount = campaignQuota.sendableCount(campaign);
        try {
            campaignQuota.reserve(principal, amount);
        } catch (ApiException quotaRefused) {
            String id = campaign.hasNonNull("id") ? campaign.get("id").asText() : null;
            if (id != null) {
                try {
                    tenantsClient.transitionCampaign(principal.tenantId(), principal.keyId(), id,
                            "cancel");
                } catch (RuntimeException e) {
                    // The campaign is over quota and could not be cancelled. It stays DRAFT and the
                    // partner is still told why; logged because a stranded draft is ours to clean up.
                    log.warn("Could not cancel over-quota campaign {} at {}: {}", id, phase,
                            e.getMessage());
                }
            }
            throw quotaRefused;
        }
        if (campaign instanceof ObjectNode mutable) {
            // Echo what the reservation actually charged for. A partner reconciling its own numbers
            // should not have to re-derive it from four count fields.
            mutable.put("quotaReserved", amount);
        }
    }

    /** The two upstream failure kinds in our envelope, as every proxying controller does it. */
    private JsonNode proxy(java.util.function.Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }
}
