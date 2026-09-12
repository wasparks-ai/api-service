package com.wasparks.api.v1;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.RequiredScope;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.InternalDtos;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Message lookup (epic §B7) — the polling alternative to webhooks.
 *
 * <p>Webhooks are the recommended way to learn a message's outcome, but they require a public endpoint.
 * This exists for the integrations that cannot have one: a cron job, a desktop tool, a first prototype.
 */
@RestController
@RequestMapping("/v1/messages")
@RequiredArgsConstructor
@Tag(name = "Messages", description = "Look up a message you sent.")
public class MessagesController {

    private static final String PUBLIC_PREFIX = "msg_";

    private final InternalTenantsClient tenantsClient;

    @GetMapping("/{id}")
    @RequiredScope(Scope.MESSAGES_READ)
    @PreAuthorize("hasAuthority('SCOPE_messages:read')")
    @Operation(summary = "Get a message",
            description = """
                    Returns the current state of a message you sent, including the Meta `wamid` once it
                    exists and the failure reason if it has one.

                    `status` is `QUEUED` while the send is still on our queue, then `SENT`, `DELIVERED`,
                    `READ` or `FAILED`. A sandbox (`wsk_test_`) message reports a `wamid` beginning
                    `DRYRUN-` and never reaches WhatsApp.""")
    public Map<String, Object> get(@PathVariable String id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        UUID messageId = parseId(id);

        try {
            return toPublic(tenantsClient.getMessage(principal, messageId));
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    /**
     * Accepts both {@code msg_<uuid>} and a bare uuid.
     *
     * <p>The prefixed form is what we hand out, so it is what a client will paste back. Accepting the
     * bare uuid too costs nothing and saves an integration that stored the id stripped from getting a
     * 404 it cannot diagnose.
     */
    private UUID parseId(String id) {
        String raw = id.startsWith(PUBLIC_PREFIX) ? id.substring(PUBLIC_PREFIX.length()) : id;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND,
                    "`" + id + "` is not a WaSparks message id.");
        }
    }

    /**
     * Translate upstream's projection into the public shape.
     *
     * <p>Two translations matter. The id is re-prefixed, so a client only ever sees {@code msg_…} and
     * never learns that the bare uuid is the real primary key. And upstream's {@code PENDING} — meaning
     * "the row exists, Meta has not answered" — becomes our {@code QUEUED}, which is the word the send
     * response and the documentation use. Leaking a second vocabulary for the same state would be a
     * support question forever.
     */
    private Map<String, Object> toPublic(InternalDtos.MessageProjection projection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", PUBLIC_PREFIX + projection.messageId());
        body.put("wamid", projection.wamid());
        body.put("status", publicStatus(projection.status()));
        body.put("to", projection.to());
        body.put("type", projection.type());
        body.put("templateName", projection.templateName());
        body.put("clientRef", projection.clientRef());
        if (projection.error() != null) {
            body.put("error", Map.of(
                    "code", projection.error().code() == null ? "" : projection.error().code(),
                    "message", projection.error().message() == null
                            ? "" : projection.error().message()));
        }
        body.put("timestamps", projection.timestamps());
        return body;
    }

    private String publicStatus(String upstreamStatus) {
        return "PENDING".equals(upstreamStatus) ? "QUEUED" : upstreamStatus;
    }
}
