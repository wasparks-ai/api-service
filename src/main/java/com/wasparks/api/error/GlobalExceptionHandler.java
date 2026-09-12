package com.wasparks.api.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns everything a controller can throw into one of the two error dialects (see {@link ErrorBodies}).
 *
 * <p>Two things are worth knowing. First, {@code @Valid} failures on the Meta send path must come back as
 * Meta code 100, not as a Spring field-error blob, because that is exactly the case a migrating client's
 * existing error handling will hit first — so bean-validation failures are funnelled through the same
 * {@link ApiErrorCode} table as everything else. Second, the handler never logs an {@link ApiException}
 * at error level: a 429 or a closed 24h window is the API working as designed, and logging it as an
 * error makes the real 500s impossible to find.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest request) {
        log.debug("Rejected {} {} -> {} {}", request.getMethod(), request.getRequestURI(),
                ex.getCode().getStatus().value(), ex.getCode().wire());
        return render(ex, request);
    }

    /** Bean validation on a request body. Field errors become {@code details.fieldErrors}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                HttpServletRequest request) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        String first = fieldErrors.isEmpty() ? null
                : fieldErrors.keySet().iterator().next() + ": " + fieldErrors.values().iterator().next();
        ApiException api = ApiException.of(ApiErrorCode.VALIDATION_FAILED, first)
                .withDetail("fieldErrors", fieldErrors);
        return render(api, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex,
                                                                HttpServletRequest request) {
        // The message carries the Jackson path of the offending field, which is genuinely useful to a
        // client debugging a strict DTO — but it also carries the class name, so only the first clause
        // (up to the first semicolon) is surfaced.
        String detail = ex.getMostSpecificCause().getMessage();
        if (detail != null && detail.contains(";")) {
            detail = detail.substring(0, detail.indexOf(';'));
        }
        return render(ApiException.of(ApiErrorCode.MALFORMED_BODY, detail), request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadParam(Exception ex, HttpServletRequest request) {
        return render(ApiException.of(ApiErrorCode.INVALID_REQUEST, ex.getMessage()), request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandler(NoHandlerFoundException ex,
                                                               HttpServletRequest request) {
        return render(ApiException.of(ApiErrorCode.NOT_FOUND, "No such endpoint."), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        // Never surface the exception message: it can carry a connection string or a decrypted value.
        return render(ApiException.of(ApiErrorCode.INTERNAL_ERROR), request);
    }

    private ResponseEntity<Map<String, Object>> render(ApiException ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ErrorBodies.HEADER_ERROR, ex.getCode().wire());
        ex.getHeaders().forEach(headers::set);

        Map<String, Object> body = ErrorBodies.isMetaPath(request.getRequestURI())
                ? ErrorBodies.meta(ex)
                : ErrorBodies.v1(ex);
        return new ResponseEntity<>(body, headers, ex.getCode().getStatus());
    }
}
