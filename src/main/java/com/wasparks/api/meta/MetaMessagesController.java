package com.wasparks.api.meta;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.RequiredScope;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.idempotency.IdempotencyService;
import com.wasparks.api.util.PhoneNumbers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Meta Cloud API-compatible send endpoint (epic §0.4, §B5).
 *
 * <p>This is the primary send shape for a reason: a business already integrated with WhatsApp has
 * working code that POSTs this exact body to this exact path. Changing the base URL and the auth header
 * should be the whole migration. Everything else about this API — our ids, our webhooks, our error codes
 * — is reachable from {@code /v1}, but the send itself speaks Meta's dialect.
 *
 * <h2>The one documented deviation</h2>
 * {@code messages[0].id} is <b>our</b> id ({@code msg_} + UUIDv7), not a Meta {@code wamid}, and
 * {@code message_status} is {@code accepted}. Meta returns a wamid because Meta has already accepted the
 * message; we have queued it, and the wamid does not exist yet. It arrives in the {@code message.sent}
 * webhook and from {@code GET /v1/messages/{id}}. Pretending otherwise would mean either blocking the
 * request until Meta answered, or inventing an id that later turns out to be wrong.
 */
@RestController
@RequestMapping("/meta/whatsapp/{version}/{phoneNumberId}")
@RequiredArgsConstructor
@Tag(name = "Meta-compatible send",
        description = "Meta Cloud API message shape. Change your base URL and auth header and your "
                + "existing integration works.")
public class MetaMessagesController {

    /** {@code v19.0} through {@code v21.0} are accepted and ignored — we call Meta with our own pin. */
    private static final Pattern VERSION = Pattern.compile("^v(19|20|21)\\.0$");

    private final MetaSendService sendService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/messages")
    @RequiredScope(Scope.MESSAGES_SEND)
    @PreAuthorize("hasAuthority('SCOPE_messages:send')")
    @Operation(summary = "Send a message",
            description = """
                    Accepts Meta's Cloud API message object and returns `202 Accepted`.

                    `messages[0].id` is the WaSparks message id (`msg_…`), not a Meta `wamid`, and
                    `message_status` is `accepted` — the send is queued, not yet delivered to Meta. The
                    `wamid` arrives in the `message.sent` webhook and from `GET /v1/messages/{id}`.
                    This is the only place the response differs from Meta's.

                    Anything we can decide up front is a synchronous error, never a 202: a malformed
                    recipient (100), an unapproved template (132001), a text outside the 24-hour window
                    (131047), an opted-out recipient (131050), a disconnected number (409), no quota
                    left (429).

                    Send `Idempotency-Key` and a retry replays the original response instead of sending
                    twice. Add a top-level `client_ref` to have your own reference echoed in every
                    webhook about this message.""")
    public ResponseEntity<Map<String, Object>> send(@PathVariable String version,
                                                    @PathVariable String phoneNumberId,
                                                    @Valid @RequestBody MetaMessageRequest request,
                                                    HttpServletRequest httpRequest) {
        requireSupportedVersion(version);

        ApiPrincipal principal = CurrentPrincipal.api();
        MetaSendService.Accepted accepted = sendService.accept(principal, phoneNumberId, request);

        Map<String, Object> body = acceptedBody(accepted);

        // Store the response under the caller's Idempotency-Key so a retry replays it verbatim. Done
        // here rather than by wrapping the output stream: the controller knows exactly what it is
        // returning, and a reconstructed body could differ from the one the client actually received.
        Object reservation = httpRequest.getAttribute(IdempotencyService.ATTRIBUTE);
        if (reservation instanceof IdempotencyService.Reservation r) {
            idempotencyService.complete(r, HttpStatus.ACCEPTED.value(), body);
        }

        return ResponseEntity.accepted().body(body);
    }

    /**
     * Meta's accept shape. {@code contacts[].input} echoes what the caller sent and {@code wa_id} is the
     * normalised number without its plus, exactly as Meta reports it — an integration that reads
     * {@code wa_id} to key its own records keeps working unchanged.
     */
    private Map<String, Object> acceptedBody(MetaSendService.Accepted accepted) {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("input", accepted.to());
        contact.put("wa_id", PhoneNumbers.toWaId(accepted.to()));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "msg_" + accepted.messageId());
        message.put("message_status", "accepted");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("contacts", List.of(contact));
        body.put("messages", List.of(message));
        return body;
    }

    private void requireSupportedVersion(String version) {
        if (!VERSION.matcher(version).matches()) {
            throw ApiException.of(ApiErrorCode.INVALID_REQUEST,
                            "Unsupported Graph API version `" + version
                                    + "`. Use v19.0 through v21.0.")
                    .withDetail("supported", List.of("v19.0", "v20.0", "v21.0"));
        }
    }
}
