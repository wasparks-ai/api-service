package com.wasparks.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.RequiredScope;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Inbound media (§B4, §0.11): a fresh download URL for an attachment a customer sent.
 *
 * <h2>Why this exists rather than Meta's URL</h2>
 * Meta's own media URLs expire quickly <em>and</em> require the account's access token to fetch — a token
 * we will never hand a partner. So an inbound image is downloaded once, at receive time, into our own
 * bucket, and what a partner gets is a signed URL to <b>our</b> copy. {@code message.received} carries one
 * valid for an hour; this endpoint is how you get another after it lapses, which is what an integration
 * that stores message ids and fetches media lazily actually needs.
 *
 * <h2>302, not a body</h2>
 * The response is a redirect to the signed URL rather than JSON containing it, so that
 * {@code <img src="https://developer.wasparks.com/v1/media/msg_…">} works, and so that a client can hand
 * the URL to any HTTP library without parsing anything. The redirect is deliberately
 * <b>{@code 302 Found}</b> and marked no-store: the target is short-lived, and a cached 301 would leave
 * a browser pointing at a signature that expired an hour ago with no way to re-ask us.
 */
@RestController
@RequestMapping("/v1/media")
@RequiredArgsConstructor
@Tag(name = "Media", description = "Download media a customer sent you.")
public class MediaController {

    /** Message ids are handed out prefixed; both forms are accepted, as on {@code /v1/messages}. */
    private static final String PUBLIC_PREFIX = "msg_";

    private final InternalTenantsClient tenantsClient;

    @GetMapping("/{messageId}")
    @RequiredScope(Scope.MEDIA_READ)
    @PreAuthorize("hasAuthority('SCOPE_media:read')")
    @Operation(summary = "Get inbound media",
            description = """
                    Redirects (`302`) to a signed URL for the media on the inbound message, valid for one
                    hour. Follow redirects and you have the bytes.

                    Use the `messageId` from `message.received`. A message with no attachment, or one
                    belonging to another tenant, is `404` — the two are deliberately the same answer.

                    The URL is ours, not Meta's: we copy inbound media into our own storage at receive
                    time, because Meta's URLs expire and need an access token you do not have.""")
    public ResponseEntity<Void> get(@PathVariable String messageId) {
        ApiPrincipal principal = CurrentPrincipal.api();

        JsonNode response;
        try {
            response = tenantsClient.mediaUrl(principal, stripPrefix(messageId));
        } catch (UpstreamRejectedException rejected) {
            if (rejected.getStatus() == 404) {
                // Upstream answers 404 both for "another tenant's message" and for "no attachment".
                // Keeping them one answer here is the same rule as everywhere else: a distinguishable
                // 404 is a way to learn which message ids exist.
                throw ApiException.of(ApiErrorCode.MEDIA_NOT_FOUND);
            }
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }

        String downloadUrl = response != null && response.hasNonNull("downloadUrl")
                ? response.get("downloadUrl").asText() : null;
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw ApiException.of(ApiErrorCode.MEDIA_NOT_FOUND);
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(downloadUrl))
                // The target carries a signature that expires within the hour. Anything that cached this
                // redirect would be caching a URL that stops working, so nothing may.
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .build();
    }

    private String stripPrefix(String messageId) {
        String raw = messageId.startsWith(PUBLIC_PREFIX)
                ? messageId.substring(PUBLIC_PREFIX.length())
                : messageId;
        try {
            return java.util.UUID.fromString(raw).toString();
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.MEDIA_NOT_FOUND,
                    "`" + messageId + "` is not a WaSparks message id.");
        }
    }
}
