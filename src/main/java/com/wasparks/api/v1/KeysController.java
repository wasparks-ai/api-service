package com.wasparks.api.v1;

import com.wasparks.api.auth.ApiKeyService;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.TenantUserPrincipal;
import com.wasparks.api.entity.ApiKey;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.plans.PlanResolver;
import com.wasparks.api.repository.ApiKeyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * API key management — <b>the one endpoint group authenticated by a tenant JWT</b> (epic §0.12).
 *
 * <p>It has to be. A tenant with no key cannot authenticate with a key, so creating the first one must
 * accept the credential they already have: their tenant-web session. Every other endpoint on this
 * service refuses a JWT outright.
 *
 * <p>Consequently there is no {@code @RequiredScope} or {@code @PreAuthorize} here — the JWT filter has
 * already established that the caller is an OWNER or ADMIN of the tenant, and a scope gate would
 * evaluate against authorities a session token does not carry.
 */
@RestController
@RequestMapping("/v1/keys")
@RequiredArgsConstructor
@Tag(name = "API keys",
        description = "Create and revoke API keys. Authenticated with a WaSparks tenant session, not "
                + "with an API key — this is where your first key comes from.")
@SecurityRequirement(name = "TenantJwt")
public class KeysController {

    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;
    private final PlanResolver planResolver;

    /**
     * {@code name} is required for a normal key and ignored for a UI-session one, which is why it is
     * validated in the handler rather than with {@code @NotBlank}: bean validation cannot express
     * "required unless another field is set", and a {@code uiSession} request that had to invent a name
     * to satisfy an annotation would be a worse contract for tenant-web than one field.
     */
    @Data
    public static class CreateRequest {
        @Size(max = 100)
        private String name;
        /** {@code LIVE} or {@code TEST}. Defaults to LIVE. */
        private String mode;
        /** Omit for all six scopes. */
        private List<String> scopes;
        /** Optional expiry. A key with no expiry is valid until revoked. */
        private Instant expiresAt;
        /**
         * Ask for a session key instead (§D.3). Every other field is ignored: the mode, scopes, name and
         * one-hour expiry are fixed, because the whole value of this key is that it cannot be widened.
         */
        private Boolean uiSession;
    }

    @GetMapping
    @Operation(summary = "List API keys",
            description = "Keys for your tenant, newest first. The key itself is never returned — only "
                    + "its prefix, which is what identifies it in this list.")
    public PagedResponse<Map<String, Object>> list() {
        TenantUserPrincipal user = CurrentPrincipal.tenantUser();
        EffectiveLimits limits = planResolver.resolve(user.tenantId());

        // UI-session keys are excluded by the query itself (§D.3) — tenant-web's own session key is not
        // one of the tenant's keys, and showing it would invite them to revoke the thing they are
        // holding the page open with.
        List<Map<String, Object>> data =
                apiKeyRepository.findByTenantIdAndUiSessionFalseOrderByCreatedAtDesc(user.tenantId())
                        .stream()
                        .map(this::toPublic)
                        .toList();

        PagedResponse<Map<String, Object>> page = PagedResponse.of(data, null);
        // The cap travels with the list so tenant-web can render "2 of 3 keys" without a second call.
        return new PagedResponse<>(page.data(), Map.of("maxKeys", limits.maxKeys()));
    }

    @PostMapping
    @Operation(summary = "Create an API key",
            description = """
                    Returns the full key **once**, as `key`. It is stored only as a SHA-256 hash and
                    cannot be shown again — if it is lost, revoke it and create another.

                    A `TEST` key behaves identically end to end but never reaches WhatsApp: messages are
                    recorded with a `DRYRUN-` wamid and their delivery receipt is synthesised, so an
                    integration can be built and tested without sending anything to a real person.

                    `{"uiSession": true}` returns the short-lived key the WaSparks dashboard uses for
                    itself — TEST mode, one hour, `webhooks:manage` and `account:read` only. It is not
                    listed, does not count against your key allowance, and is deleted once it expires.""")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest request) {
        TenantUserPrincipal user = CurrentPrincipal.tenantUser();

        if (Boolean.TRUE.equals(request.getUiSession())) {
            return created(apiKeyService.issueUiSessionKey(user.tenantId(), user.userId()));
        }

        if (request.getName() == null || request.getName().isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`name` is required (omit it only with `uiSession: true`).");
        }

        ApiKeyMode mode = parseMode(request.getMode());
        Set<String> scopes = request.getScopes() == null
                ? null : new LinkedHashSet<>(request.getScopes());

        if (request.getExpiresAt() != null && request.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`expiresAt` is in the past — the key would be unusable the moment it is created.");
        }

        ApiKeyService.IssuedKey issued = apiKeyService.issue(
                user.tenantId(), user.userId(), request.getName().trim(), mode, scopes,
                request.getExpiresAt());

        return created(issued);
    }

    private ResponseEntity<Map<String, Object>> created(ApiKeyService.IssuedKey issued) {
        Map<String, Object> body = toPublic(issued.key());
        body.put("key", issued.plaintext());
        body.put("warning", "This is the only time the key is shown. Store it now.");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke an API key",
            description = "Takes effect immediately — the cached lookup is evicted as the key is "
                    + "revoked. Revoking is permanent; issue a new key rather than expecting to "
                    + "restore this one.")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        TenantUserPrincipal user = CurrentPrincipal.tenantUser();
        apiKeyService.revoke(user.tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    private ApiKeyMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ApiKeyMode.LIVE;
        }
        try {
            return ApiKeyMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "`mode` must be LIVE or TEST.");
        }
    }

    /** Everything about a key except the key. */
    private Map<String, Object> toPublic(ApiKey key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", key.getId().toString());
        body.put("name", key.getName());
        body.put("prefix", key.getPrefix());
        body.put("mode", key.getMode().name());
        body.put("scopes", key.getScopes());
        body.put("status", key.getStatus().name());
        body.put("lastUsedAt", key.getLastUsedAt());
        body.put("expiresAt", key.getExpiresAt());
        body.put("revokedAt", key.getRevokedAt());
        body.put("createdBy", key.getCreatedBy());
        body.put("createdAt", key.getCreatedAt());
        // Present so tenant-web can assert it got a session key rather than accidentally minting a real
        // one into browser memory. Always false in the list, which never contains them.
        body.put("uiSession", key.isUiSession());
        body.put("availableScopes", Scope.allWire());
        return body;
    }
}
