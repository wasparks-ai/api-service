package com.wasparks.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.RequiredScope;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.quota.TemplateCreateLimiter;
import com.wasparks.api.usage.UsageService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * Template lifecycle (epic §B7) — a thin proxy over tenants-service's template service.
 *
 * <p>Bodies and responses pass through as JSON. The template DTO is large, is owned upstream, and this
 * service adds nothing to it: the scope check, the tenant scoping and the per-day create cap are all
 * this layer contributes. Re-declaring the DTO here would create a second copy to keep in step with
 * every template feature tenants-service ships, for no benefit to anyone.
 */
@RestController
@RequestMapping("/v1/templates")
@RequiredArgsConstructor
@Tag(name = "Templates",
        description = "Create, submit and track WhatsApp message templates. Meta must approve a "
                + "template before you can send it outside a 24-hour customer service window.")
public class TemplatesController {

    private final InternalTenantsClient tenantsClient;
    private final TemplateCreateLimiter templateCreateLimiter;
    private final UsageService usageService;
    private final ObjectMapper objectMapper;

    @GetMapping
    @RequiredScope(Scope.TEMPLATES_READ)
    @PreAuthorize("hasAuthority('SCOPE_templates:read')")
    @Operation(summary = "List templates",
            description = """
                    Filter by `status` (DRAFT, PENDING, APPROVED, REJECTED, PAUSED) and `category`.

                    **Changed on 2026-09-16.** This returns `{"data":[…],"meta":{"next_cursor"}}` like
                    every other list on this API, and pages with `cursor` and `limit`. It previously
                    returned the underlying `{"content":[…],"totalElements":…}` and took `page` and
                    `size` — if you are reading `content`, read `data`, and walk with `meta.next_cursor`
                    instead of incrementing a page number.""")
    public PagedResponse<Object> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        ApiPrincipal principal = CurrentPrincipal.api();
        int size = PagedResponse.clampLimit(limit);
        int page = UpstreamPages.decodeCursor(cursor);

        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        addIfPresent(query, "status", status);
        addIfPresent(query, "category", category);
        UpstreamPages.pageParams(page, size).forEach(query::add);

        JsonNode upstream = proxy(() -> tenantsClient.listTemplates(principal, query));
        return UpstreamPages.envelope(upstream, page, size, objectMapper);
    }

    @GetMapping("/{id}")
    @RequiredScope(Scope.TEMPLATES_READ)
    @PreAuthorize("hasAuthority('SCOPE_templates:read')")
    @Operation(summary = "Get a template")
    public JsonNode get(@PathVariable String id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return proxy(() -> tenantsClient.getTemplate(principal, id));
    }

    @PostMapping
    @RequiredScope(Scope.TEMPLATES_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_templates:write')")
    @Operation(summary = "Create a draft template",
            description = """
                    Creates a DRAFT. Submit it separately once you are happy with it — Meta review can
                    take anywhere from minutes to a day, and the outcome arrives as a
                    `template.approved` or `template.rejected` webhook.

                    Counts against your plan's daily template-create limit.""")
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body) {
        ApiPrincipal principal = CurrentPrincipal.api();
        EffectiveLimits limits = principal.limits();

        // Meta rate-limits and penalises template creation, so this cap protects the tenant's own WABA
        // standing as much as it protects us.
        templateCreateLimiter.requireAllowance(principal.tenantId(), limits.templateCreatesPerDay());

        JsonNode created = proxy(() -> tenantsClient.createTemplate(principal, body));
        usageService.increment(principal.tenantId(), principal.keyId(),
                UsageService.FIELD_TEMPLATE_CREATES);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    @RequiredScope(Scope.TEMPLATES_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_templates:write')")
    @Operation(summary = "Update a draft template",
            description = "Only DRAFT and REJECTED templates can be edited. Anything else returns 409.")
    public JsonNode update(@PathVariable String id, @RequestBody JsonNode body) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return proxy(() -> tenantsClient.updateTemplate(principal, id, body));
    }

    @PostMapping("/{id}/submit")
    @RequiredScope(Scope.TEMPLATES_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_templates:write')")
    @Operation(summary = "Submit a template to Meta for review")
    public JsonNode submit(@PathVariable String id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return proxy(() -> tenantsClient.submitTemplate(principal, id));
    }

    @PostMapping("/{id}/refresh")
    @RequiredScope(Scope.TEMPLATES_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_templates:write')")
    @Operation(summary = "Re-read a template's status from Meta",
            description = "Normally unnecessary — status changes arrive as webhooks. Use this if you "
                    + "suspect a webhook was missed.")
    public JsonNode refresh(@PathVariable String id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return proxy(() -> tenantsClient.refreshTemplate(principal, id));
    }

    @DeleteMapping("/{id}")
    @RequiredScope(Scope.TEMPLATES_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_templates:write')")
    @Operation(summary = "Delete a template",
            description = "A submitted template is deleted at Meta first. Messages already sent with "
                    + "it are unaffected.")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        proxyVoid(() -> tenantsClient.deleteTemplate(principal, id));
        return ResponseEntity.noContent().build();
    }

    private void addIfPresent(MultiValueMap<String, String> query, String name, String value) {
        if (value != null && !value.isBlank()) {
            query.add(name, value);
        }
    }

    /**
     * Run an upstream call and translate its two failure kinds into our envelope. Every method here
     * needs the identical treatment, so it lives in one place rather than six catch blocks.
     */
    private JsonNode proxy(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    private void proxyVoid(Runnable call) {
        try {
            call.run();
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }
}
