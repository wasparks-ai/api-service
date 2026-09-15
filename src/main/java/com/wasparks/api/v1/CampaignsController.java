package com.wasparks.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.RequiredScope;
import com.wasparks.api.campaign.CampaignService;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * Bulk template sends (§B3). Works with a tenant key on the tenant's own campaigns and with a partner
 * key on any of its customers' — {@code X-Tenant-Id} decides which, and everything below simply reads the
 * acting tenant off the principal.
 *
 * <p>The campaign runs on the <b>existing runner</b>: the same tier caps, the same warm-up ramp, the same
 * quality auto-pause and the same suppression list as a campaign built in our own UI (§0.8). What the API
 * adds is who created it — the key is stamped on the row — and the quota it costs.
 */
@RestController
@RequestMapping("/v1/campaigns")
@RequiredArgsConstructor
@Tag(name = "Campaigns",
        description = "Send an approved template to a list of recipients, and follow it with webhooks.")
public class CampaignsController {

    private final CampaignService campaignService;
    private final InternalTenantsClient tenantsClient;
    private final ObjectMapper objectMapper;

    @PostMapping
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Create a campaign",
            description = """
                    Send `audience` with **exactly one** of `recipients` (up to 5,000 per request),
                    `audienceId` (a list you saved earlier) or `csvUploadId` (from `POST /v1/uploads/csv`).

                    Omit `schedule` for a **DRAFT** you start yourself; send `schedule.startAt` for a
                    **SCHEDULED** campaign the runner promotes at that time.

                    `recipients[].vars` are keyed by the template's placeholder number — `{"1": "Rahul"}`
                    fills `{{1}}`. The CSV path is the exception: there you map placeholders to column
                    names.

                    **Nothing is dropped silently.** Recipients on the suppression list, those skipped by
                    the marketing frequency guard and those whose number could not be parsed all come back
                    in `counts` as `suppressed`, `frequencySkipped` and `invalid`, and each has a row in
                    `GET /v1/campaigns/{id}/recipients`.

                    **Quota.** A campaign reserves its sendable recipients — `total` minus those three —
                    against your daily and monthly allowance. A campaign that starts now reserves at
                    create; a scheduled one reserves shortly before it starts, because its allowance
                    belongs to that day rather than to today. If the allowance is gone when a scheduled
                    campaign comes due, it is paused and you get `campaign.paused` with
                    `reason: "QUOTA"`.

                    `respectQuietHours` is **accepted and reserved: it currently enforces nothing.**
                    Campaign sending has no quiet-hours engine. The field exists so the body shape is
                    stable; do not rely on it to hold messages back.""")
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(campaignService.create(principal, body));
    }

    @GetMapping
    @RequiredScope(Scope.CAMPAIGNS_READ)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:read')")
    @Operation(summary = "List campaigns",
            description = "Newest first. Filter by `status` (DRAFT, SCHEDULED, RUNNING, PAUSED, "
                    + "COMPLETED, CANCELLED).")
    public PagedResponse<Object> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        ApiPrincipal principal = CurrentPrincipal.api();
        int size = PagedResponse.clampLimit(limit);
        int page = UpstreamPages.decodeCursor(cursor);

        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        addIfPresent(query, "status", status);
        UpstreamPages.pageParams(page, size).forEach(query::add);

        JsonNode upstream = proxy(() ->
                tenantsClient.listCampaigns(principal.tenantId(), principal.keyId(), query));
        return UpstreamPages.envelope(upstream, page, size, objectMapper);
    }

    @GetMapping("/{id}")
    @RequiredScope(Scope.CAMPAIGNS_READ)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:read')")
    @Operation(summary = "Get a campaign",
            description = "Includes `counts` broken down by outcome, the timestamps, and "
                    + "`pausedReason` when it is paused.")
    public JsonNode get(@PathVariable String id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return proxy(() -> tenantsClient.getCampaign(principal.tenantId(), principal.keyId(), id));
    }

    @GetMapping("/{id}/recipients")
    @RequiredScope(Scope.CAMPAIGNS_READ)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:read')")
    @Operation(summary = "List a campaign's recipients",
            description = """
                    Every recipient with its outcome, **including the ones that were not sent to** —
                    filter with `status=SUPPRESSED`, `SKIPPED_FREQUENCY` or `INVALID` to see exactly who
                    and why.""")
    public PagedResponse<Object> recipients(@PathVariable String id,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        ApiPrincipal principal = CurrentPrincipal.api();
        int size = PagedResponse.clampLimit(limit);
        int page = UpstreamPages.decodeCursor(cursor);

        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        addIfPresent(query, "status", status);
        UpstreamPages.pageParams(page, size).forEach(query::add);

        JsonNode upstream = proxy(() -> tenantsClient.campaignRecipients(principal, id, query));
        return UpstreamPages.envelope(upstream, page, size, objectMapper);
    }

    @PostMapping("/{id}/recipients")
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Add recipients to a campaign",
            description = "DRAFT and SCHEDULED campaigns only, up to 5,000 per request. This is how a "
                    + "list larger than one request is built.")
    public JsonNode appendRecipients(@PathVariable String id, @RequestBody JsonNode body) {
        return campaignService.appendRecipients(CurrentPrincipal.api(), id, body);
    }

    @PostMapping("/{id}/start")
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Start a campaign",
            description = "Moves a DRAFT to SCHEDULED; the runner picks it up on its next tick. The "
                    + "campaign's quota is reserved here, so a start can be refused with 429 while the "
                    + "campaign stays a draft you can start tomorrow.")
    public JsonNode start(@PathVariable String id) {
        return campaignService.start(CurrentPrincipal.api(), id);
    }

    @PostMapping("/{id}/pause")
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Pause a campaign",
            description = "Stops sending. Recipients already sent to are unaffected.")
    public JsonNode pause(@PathVariable String id) {
        return campaignService.transition(CurrentPrincipal.api(), id, "pause");
    }

    @PostMapping("/{id}/resume")
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Resume a paused campaign")
    public JsonNode resume(@PathVariable String id) {
        return campaignService.transition(CurrentPrincipal.api(), id, "resume");
    }

    @PostMapping("/{id}/cancel")
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Cancel a campaign",
            description = "Permanent. Recipients not yet sent to never will be.")
    public JsonNode cancel(@PathVariable String id) {
        return campaignService.transition(CurrentPrincipal.api(), id, "cancel");
    }

    private void addIfPresent(MultiValueMap<String, String> query, String name, String value) {
        if (value != null && !value.isBlank()) {
            query.add(name, value);
        }
    }

    private JsonNode proxy(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }
}
