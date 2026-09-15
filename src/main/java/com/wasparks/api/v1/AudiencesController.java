package com.wasparks.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * Saved recipient lists (§B3a).
 *
 * <p>Pulled into P1 for one reason, and it is worth stating because it shapes the API: partners re-send
 * to the <b>same list every few days with a different template</b> — a new listing, a price change, an
 * open house. Without audiences, every one of those sends would mean re-uploading the same few thousand
 * numbers, and each upload would be a fresh chance for the two sides' copies to diverge.
 *
 * <p>These are STATIC lists: a fixed set of phone numbers with variables. Dynamic segments — filters over
 * contact fields — exist in our own UI and can still back a campaign through {@code audienceId}, but they
 * cannot be created here, because they filter on fields a partner does not populate.
 */
@RestController
@RequestMapping("/v1/audiences")
@RequiredArgsConstructor
@Tag(name = "Audiences",
        description = "Save a recipient list once and send to it repeatedly.")
public class AudiencesController {

    private final InternalTenantsClient tenantsClient;
    private final CampaignService campaignService;

    @PostMapping
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Create an audience",
            description = """
                    `{"name": "Sector 45 buyers", "members": [{"phone": "9198…", "vars": {"1": "Rahul"}}]}`,
                    up to 5,000 members per request — append the rest.

                    `vars` are keyed by placeholder number and are merged into the template at send time,
                    so one list can back templates with different variables as long as the numbering
                    lines up.

                    A duplicate name is a `409`.""")
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body) {
        ApiPrincipal principal = CurrentPrincipal.api();
        campaignService.requireInlineLimit(body, "members");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(proxy(() -> tenantsClient.createAudience(principal, body)));
    }

    @GetMapping
    @RequiredScope(Scope.CAMPAIGNS_READ)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:read')")
    @Operation(summary = "List audiences")
    public JsonNode list(@RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return proxy(() -> tenantsClient.listAudiences(principal, paging(page, size)));
    }

    @GetMapping("/{id}")
    @RequiredScope(Scope.CAMPAIGNS_READ)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:read')")
    @Operation(summary = "Get an audience")
    public JsonNode get(@PathVariable String id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return proxy(() -> tenantsClient.getAudience(principal, id));
    }

    @GetMapping("/{id}/members")
    @RequiredScope(Scope.CAMPAIGNS_READ)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:read')")
    @Operation(summary = "List an audience's members",
            description = "Each member's phone, status and variables.")
    public JsonNode members(@PathVariable String id,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return proxy(() -> tenantsClient.audienceMembers(principal, id, paging(page, size)));
    }

    @PostMapping("/{id}/members")
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Add or update members",
            description = """
                    Appends new phones and updates existing ones. **Variables are merged, not replaced** —
                    sending only the field you changed leaves the rest alone, so a partner updating one
                    name does not have to restate a member's whole record.""")
    public JsonNode addMembers(@PathVariable String id, @RequestBody JsonNode body) {
        ApiPrincipal principal = CurrentPrincipal.api();
        campaignService.requireInlineLimit(body, "members");
        return proxy(() -> tenantsClient.addAudienceMembers(principal, id, body));
    }

    @DeleteMapping("/{id}/members")
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Remove members",
            description = "Body `{\"phones\": [\"9198…\"]}`. A phone that is not in the list is not an "
                    + "error — removal is idempotent, which is what a partner syncing its own "
                    + "unsubscribes needs.")
    public JsonNode removeMembers(@PathVariable String id, @RequestBody JsonNode body) {
        ApiPrincipal principal = CurrentPrincipal.api();
        if (body == null || !body.hasNonNull("phones") || !body.get("phones").isArray()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Send `phones` as an array of numbers to remove.");
        }
        campaignService.requireInlineLimit(body, "phones");
        return proxy(() -> tenantsClient.removeAudienceMembers(principal, id, body));
    }

    @DeleteMapping("/{id}")
    @RequiredScope(Scope.CAMPAIGNS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_campaigns:write')")
    @Operation(summary = "Delete an audience",
            description = "`409 audience_in_use` while a scheduled or running campaign still points at "
                    + "it — deleting it then would leave that campaign with nobody to send to.")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        try {
            tenantsClient.deleteAudience(principal, id);
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }
        return ResponseEntity.noContent().build();
    }

    private MultiValueMap<String, String> paging(Integer page, Integer size) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        if (page != null) {
            query.add("page", String.valueOf(page));
        }
        if (size != null) {
            query.add("size", String.valueOf(PagedResponse.clampLimit(size)));
        }
        return query;
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
