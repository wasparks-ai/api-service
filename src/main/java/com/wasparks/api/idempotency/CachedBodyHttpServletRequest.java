package com.wasparks.api.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body can be read more than once.
 *
 * <p>A servlet input stream is single-pass. The idempotency check has to hash the exact bytes the client
 * sent <em>before</em> the handler runs — it is deciding whether to run the handler at all — and Spring
 * then has to read the same bytes to bind the DTO. Spring's own {@code ContentCachingRequestWrapper}
 * caches on the way past, so it is empty at the point we need it; this one reads the body up front and
 * replays it.
 *
 * <p>Only installed for requests that actually carry an {@code Idempotency-Key}
 * ({@link BodyCachingFilter}), so the memory cost is paid by the requests that need it and by no others.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    /** The raw bytes, for hashing. Never mutated — callers get the array this wrapper replays. */
    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Async reads are not used on this path");
            }

            @Override
            public int read() {
                return buffer.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }
}
