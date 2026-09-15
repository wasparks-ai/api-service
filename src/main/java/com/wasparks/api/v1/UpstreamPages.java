package com.wasparks.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an upstream page into the {@code /v1} list envelope (epic §B7: {@code {data, meta}}).
 *
 * <p>tenants-service pages with Spring's {@code Page} — {@code {content, number, size, totalElements,
 * last, …}} — and this API promises {@code {data, meta:{next_cursor}}}. Every proxying list endpoint has
 * to bridge the two, and doing it in each controller is how one of them ends up not doing it: the
 * cross-client campaign list returned the envelope while the single-customer path beside it returned
 * upstream's raw page, so a client that passed {@code customerId} got a different shape from one that
 * did not. Same endpoint, same caller, two contracts.
 *
 * <h2>The cursor is a page number</h2>
 * Upstream pages by offset; the envelope promises a cursor. So the cursor is the page number, base64url
 * encoded — opaque enough that a caller reads it as a token rather than doing arithmetic on it, which
 * keeps the encoding ours to replace the day any of these lists gets a real keyset cursor. A caller that
 * loops until {@code next_cursor} is absent never notices the difference either way.
 *
 * <p>Offset paging has the failure the {@code PagedResponse} javadoc warns about — rows shifting under a
 * walker on an append-heavy list — and it is upstream's to fix, not something this layer can paper over.
 * What this layer can do is not promise more than it delivers, which is why the cursor is opaque.
 */
public final class UpstreamPages {

    /**
     * The field names upstream has used for the rows, in the order they are looked for.
     *
     * <p>{@code content} is Spring's paged shape, which most of the internal surface returns;
     * {@code items} is the keyset list ({@code /internal/v1/campaigns/across}).
     */
    private static final List<String> ROW_FIELDS = List.of("content", "items", "data");

    private UpstreamPages() {
    }

    /** Read a caller's cursor back into a page number. Absent means the first page. */
    public static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int page = Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor.trim()),
                    StandardCharsets.UTF_8));
            if (page < 0) {
                throw new NumberFormatException(cursor);
            }
            return page;
        } catch (RuntimeException e) {
            // Not one of ours. Treating it as page zero would silently restart a walk the caller thought
            // it was continuing, which is worse than saying so.
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`cursor` is not one we issued. Omit it to start from the first page.");
        }
    }

    public static String encodeCursor(int page) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(page).getBytes(StandardCharsets.UTF_8));
    }

    /** The {@code page}/{@code size} an upstream call needs for this cursor. */
    public static Map<String, String> pageParams(int page, int size) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", String.valueOf(page));
        params.put("size", String.valueOf(size));
        return params;
    }

    public static PagedResponse<Object> envelope(JsonNode upstream, int page, int size,
                                                 ObjectMapper mapper) {
        return envelope(upstream, page, size, mapper, Map.of());
    }

    /**
     * The envelope, with {@code next_cursor} present only when there is another page.
     *
     * <p>"Another page" is taken from upstream when it says so ({@code last}, or {@code totalPages}) and
     * inferred from a full page of rows when it does not. The inference costs one empty last page in the
     * exact-multiple case; asserting a total that upstream did not send would cost a wrong answer.
     */
    public static PagedResponse<Object> envelope(JsonNode upstream, int page, int size,
                                                 ObjectMapper mapper,
                                                 Map<String, Object> extraMeta) {
        List<Object> rows = rows(upstream, mapper);
        String next = hasMore(upstream, rows.size(), page, size) ? encodeCursor(page + 1) : null;

        Map<String, Object> meta = new LinkedHashMap<>(extraMeta);
        if (next != null) {
            meta.put("next_cursor", next);
        }
        return new PagedResponse<>(rows, meta.isEmpty() ? Map.of() : meta);
    }

    /**
     * The envelope for an upstream list that already pages by <b>keyset</b>, whose cursor is passed
     * through in both directions rather than being re-encoded here.
     *
     * <p>{@code /internal/v1/campaigns/across} is the only one today. Its cursor is a
     * {@code created_at|id} pair, its rows are under {@code items}, and it says plainly whether there is
     * more ({@code nextCursor} null on the last page, plus {@code hasMore}) — so none of the inference
     * the offset variant has to do applies, and doing it anyway would be inventing an answer next to a
     * correct one.
     *
     * <p>{@code rows} is taken as a parameter rather than read from the body because the caller decorates
     * each row before it goes out; the cursor and the "is there more" question still come from upstream.
     */
    public static PagedResponse<Object> keysetEnvelope(List<Object> rows, JsonNode upstream,
                                                       Map<String, Object> extraMeta) {
        Map<String, Object> meta = new LinkedHashMap<>(extraMeta);
        String next = upstream != null && upstream.hasNonNull("nextCursor")
                ? upstream.get("nextCursor").asText()
                : null;
        // hasMore is upstream's own answer and wins; nextCursor being null IS the end of the walk, so a
        // hasMore of true with no cursor would be a contradiction we should not paper over by guessing.
        boolean hasMore = upstream != null && upstream.hasNonNull("hasMore")
                ? upstream.get("hasMore").asBoolean()
                : next != null;
        if (hasMore && next != null && !next.isBlank()) {
            meta.put("next_cursor", next);
        }
        return new PagedResponse<>(rows, meta.isEmpty() ? Map.of() : meta);
    }

    /** The envelope for rows this service assembled itself rather than proxied. */
    public static PagedResponse<Object> envelope(List<Object> rows, int page, int size,
                                                 Map<String, Object> extraMeta) {
        Map<String, Object> meta = new LinkedHashMap<>(extraMeta);
        if (rows.size() >= size) {
            meta.put("next_cursor", encodeCursor(page + 1));
        }
        return new PagedResponse<>(rows, meta.isEmpty() ? Map.of() : meta);
    }

    /**
     * The rows, wherever upstream put them.
     *
     * <p>A bare array and the three field names are all accepted, because the shape of a list is the kind
     * of thing that gets changed without anyone thinking of it as a contract change — and the honest
     * failure for an unrecognised shape is an empty list plus whatever the caller sees in the logs, not
     * a 500 on a read.
     */
    public static List<Object> rows(JsonNode upstream, ObjectMapper mapper) {
        List<Object> rows = new ArrayList<>();
        JsonNode array = rowsNode(upstream);
        if (array != null) {
            array.forEach(row -> rows.add(mapper.convertValue(row, Object.class)));
        }
        return rows;
    }

    public static JsonNode rowsNode(JsonNode upstream) {
        if (upstream == null) {
            return null;
        }
        if (upstream.isArray()) {
            return upstream;
        }
        for (String field : ROW_FIELDS) {
            JsonNode value = upstream.get(field);
            if (value != null && value.isArray()) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasMore(JsonNode upstream, int rowCount, int page, int size) {
        if (upstream != null && upstream.isObject()) {
            if (upstream.hasNonNull("last") && upstream.get("last").isBoolean()) {
                return !upstream.get("last").asBoolean();
            }
            if (upstream.hasNonNull("totalPages") && upstream.get("totalPages").isNumber()) {
                return page + 1 < upstream.get("totalPages").asInt();
            }
        }
        return rowCount >= size && rowCount > 0;
    }
}
