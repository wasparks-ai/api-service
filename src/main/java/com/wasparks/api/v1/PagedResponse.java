package com.wasparks.api.v1;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The {@code /v1} list envelope (epic §B7): {@code {"data":[…],"meta":{"next_cursor":…}}}.
 *
 * <p>Cursor pagination rather than page numbers. These lists are append-heavy — deliveries, keys,
 * messages — and with offsets a caller walking pages while new rows arrive sees items twice or misses
 * them entirely. A cursor anchored to the last item it actually saw does not have that failure.
 *
 * <p>{@code next_cursor} is absent, not null, on the last page: a client testing for its presence is
 * the common idiom and should not have to distinguish the two.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PagedResponse<T>(List<T> data, Map<String, Object> meta) {

    /** Default page size when the caller does not ask, and the ceiling on what it may ask for. */
    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 100;

    public static <T> PagedResponse<T> of(List<T> data, String nextCursor) {
        Map<String, Object> meta = nextCursor == null
                ? Map.of()
                : Map.of("next_cursor", nextCursor);
        return new PagedResponse<>(data, meta);
    }

    /** Clamp a caller-supplied limit into range. A limit of zero or below means "use the default". */
    public static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
