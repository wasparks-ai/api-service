package com.wasparks.api.queue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wasparks.api.auth.PartnerPrincipal;
import com.wasparks.api.internal.InternalDtos;

import java.util.UUID;

/**
 * One unit of work on the {@code sends} stream (epic §B5, §B6).
 *
 * <p>It carries the <b>fully translated</b> internal send request, not the original Meta body. Validation
 * and translation happen on the request thread, while the client is still there to be told about a
 * problem; by the time a job exists the only thing left that can fail is the send itself. A worker that
 * had to re-parse a Meta body would be re-deciding questions the client can no longer hear the answer to.
 *
 * <p>{@code tenantId} and {@code apiKeyId} travel with the job because the worker has no request and no
 * security context — they are what the internal call is scoped and audited by. {@code partner} travels
 * for the same reason and is null on an ordinary tenant key: when a send is refused upstream the worker
 * has to hand the reservation back, and on a partner key that means the partner's pool counters and
 * possibly the client's cap, none of which can be derived from a tenant id alone.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SendJob(
        UUID messageId,
        UUID tenantId,
        UUID apiKeyId,
        String mode,
        InternalDtos.SendRequest payload,
        String clientRef,
        PartnerPrincipal partner) {

    /** Public id form (epic §0 open forks: prefixed is the default). */
    public String publicId() {
        return "msg_" + messageId;
    }
}
