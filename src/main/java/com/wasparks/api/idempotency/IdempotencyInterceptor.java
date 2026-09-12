package com.wasparks.api.idempotency;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

/**
 * Third in the pipeline (epic hand-off §4), and applied to send endpoints only.
 *
 * <p>On a replay it writes the stored response straight to the servlet output and returns {@code false},
 * so the handler never runs — no message is enqueued, no quota is consumed, nothing upstream is called.
 * That is the whole point: the second request must be inert.
 *
 * <p>On a fresh key it stashes the reservation on the request and lets the handler run; the controller
 * completes the record with its own response body (see {@code IdempotencySupport}), which is more
 * faithful than trying to reconstruct the response by wrapping the output stream.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final IdempotencyService idempotencyService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String idempotencyKey = request.getHeader(IdempotencyService.HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;   // the header is optional (epic §B4) — strongly recommended, not required
        }

        ApiPrincipal principal = CurrentPrincipal.fromRequest(request);
        if (principal == null) {
            return true;
        }

        byte[] body = request instanceof CachedBodyHttpServletRequest cached
                ? cached.body()
                : new byte[0];

        Optional<IdempotencyService.Record> replay = idempotencyService.beginOrReplay(
                principal, idempotencyKey.trim(), body,
                reservation -> request.setAttribute(IdempotencyService.ATTRIBUTE, reservation));

        if (replay.isEmpty()) {
            return true;
        }

        IdempotencyService.Record record = replay.get();
        response.setStatus(record.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(IdempotencyService.HEADER_REPLAYED, "true");
        response.getWriter().write(record.responseBody());
        response.flushBuffer();
        log.debug("Replayed idempotent response for key {} on {}", idempotencyKey,
                request.getRequestURI());
        return false;
    }

    /**
     * Release the reservation when the handler did not produce a success.
     *
     * <p>{@code afterCompletion} is the only hook that runs on both the thrown and the returned path, and
     * a reservation left behind by a failure would lock the client's key for the full TTL.
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        Object stashed = request.getAttribute(IdempotencyService.ATTRIBUTE);
        if (!(stashed instanceof IdempotencyService.Reservation reservation)) {
            return;
        }
        boolean succeeded = ex == null && response.getStatus() >= 200 && response.getStatus() < 300;
        if (!succeeded) {
            idempotencyService.release(reservation);
        }
    }
}
