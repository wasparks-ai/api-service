package com.wasparks.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Writes an {@link ApiException} straight to the servlet response, for the layers that run
 * <b>outside</b> Spring MVC's exception handling: the authentication filters and the interceptors.
 *
 * <p>A {@code @RestControllerAdvice} only sees what reaches a controller, so a filter rejecting a bad key
 * and an interceptor rejecting an over-limit request would otherwise produce Tomcat's default HTML error
 * page — in the wrong dialect and without our headers. Both paths funnel through here instead, and the
 * dialect is chosen by path exactly as the advice chooses it.
 */
@Component
@RequiredArgsConstructor
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletRequest request, HttpServletResponse response, ApiException ex)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(ex.getCode().getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(ErrorBodies.HEADER_ERROR, ex.getCode().wire());
        ex.getHeaders().forEach(response::setHeader);

        Map<String, Object> body = ErrorBodies.isMetaPath(request.getRequestURI())
                ? ErrorBodies.meta(ex)
                : ErrorBodies.v1(ex);
        objectMapper.writeValue(response.getOutputStream(), body);
        response.flushBuffer();
    }
}
