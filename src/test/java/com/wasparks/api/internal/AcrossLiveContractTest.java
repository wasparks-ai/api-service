package com.wasparks.api.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.v1.PagedResponse;
import com.wasparks.api.v1.UpstreamPages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link InternalTenantsClient#campaignsAcross} against a <b>running tenants-service</b>.
 *
 * <p>Off by default and never part of a build: it needs a real service on 8081 and real rows in the dev
 * database, neither of which CI has. Run it deliberately, after seeding:
 *
 * <pre>
 *   mvn test -Dtest=AcrossLiveContractTest -Dlive.tenants=true
 * </pre>
 *
 * <h2>Why it exists</h2>
 * The stubbed tests for this endpoint all passed while the client was reading {@code content} from a
 * response that says {@code items} — because the stubs had been written from a path name rather than from
 * {@code internal.md}, so they agreed with the bug. Every layer was self-consistent and the merged list
 * would have been empty in production. The only thing that catches that class of mistake is talking to
 * the actual service, so there is one test that does.
 *
 * <p>It asserts the two things a stub cannot: that the real body parses into our envelope, and that a
 * cursor taken from page one really fetches page two.
 */
@EnabledIfSystemProperty(named = "live.tenants", matches = "true")
class AcrossLiveContractTest {

    private static final String BASE_URL = "http://localhost:8081";
    private static final String SECRET =
            "ef82f6cb4031c4fae5744f1810686bf56c9340bc53e9f91d33d56cb7d984049b";

    /** Seeded by the fixture in the hand-off: one partner, two clients, six interleaved campaigns. */
    private static final UUID PARTNER_TENANT = UUID.fromString("aa000000-0000-4000-8000-000000000001");
    private static final UUID CLIENT_A = UUID.fromString("aa000000-0000-4000-8000-000000000002");
    private static final UUID CLIENT_B = UUID.fromString("aa000000-0000-4000-8000-000000000003");
    private static final UUID ACTOR_KEY = UUID.fromString("aa000000-0000-4000-8000-0000000000e1");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private InternalTenantsClient client() {
        InternalTenantsClient client = new InternalTenantsClient(BASE_URL, SECRET, 10000);
        client.init();
        return client;
    }

    @Test
    @DisplayName("live: the merged list pages across two tenants by keyset, into our envelope")
    void mergedListAcrossTwoTenants() {
        InternalTenantsClient client = client();
        List<UUID> tenants = List.of(CLIENT_A, CLIENT_B);

        JsonNode first = client.campaignsAcross(PARTNER_TENANT, ACTOR_KEY, tenants, null, null, 3);
        PagedResponse<Object> page1 = UpstreamPages.keysetEnvelope(
                UpstreamPages.rows(first, objectMapper), first, Map.of());

        // The bug in one assertion: rows live under `items`, and a client reading `content` got none.
        assertThat(page1.data()).hasSize(3);
        assertThat(names(page1)).containsExactly("ACROSS-LIVE-B3", "ACROSS-LIVE-A3", "ACROSS-LIVE-B2");
        assertThat(tenantIds(page1))
                .as("the page spans both customers — an offset would have split them wrongly")
                .contains(CLIENT_A.toString(), CLIENT_B.toString());

        String cursor = (String) page1.meta().get("next_cursor");
        assertThat(cursor).as("hasMore was true, so the envelope must carry a cursor").isNotBlank();

        JsonNode second = client.campaignsAcross(PARTNER_TENANT, ACTOR_KEY, tenants, null, cursor, 3);
        PagedResponse<Object> page2 = UpstreamPages.keysetEnvelope(
                UpstreamPages.rows(second, objectMapper), second, Map.of());

        assertThat(names(page2)).containsExactly("ACROSS-LIVE-A2", "ACROSS-LIVE-B1", "ACROSS-LIVE-A1");
        assertThat(names(page2)).doesNotContainAnyElementsOf(names(page1));

        // The real service OMITS nextCursor on the last page rather than sending null, which the
        // documented shape describes as null. Both have to read as "no more", and they do.
        assertThat(second.has("nextCursor")).isFalse();
        assertThat(second.get("hasMore").asBoolean()).isFalse();
        assertThat(page2.meta()).doesNotContainKey("next_cursor");
    }

    @Test
    @DisplayName("live: a malformed cursor is refused rather than restarting the walk")
    void malformedCursorIsRefused() {
        assertThatThrownBy(() -> client().campaignsAcross(PARTNER_TENANT, ACTOR_KEY,
                List.of(CLIENT_A, CLIENT_B), null, "garbage", 3))
                .isInstanceOf(UpstreamRejectedException.class)
                .satisfies(thrown -> {
                    UpstreamRejectedException rejected = (UpstreamRejectedException) thrown;
                    assertThat(rejected.getStatus()).isEqualTo(400);
                    assertThat(rejected.getCode()).isEqualTo("invalid_request");
                });
    }

    @Test
    @DisplayName("live: more than 50 tenant ids is refused upstream — the local guard exists to prevent it")
    void tenantBoundIsReal() {
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < InternalTenantsClient.ACROSS_MAX_TENANTS + 1; i++) {
            tooMany.add(UUID.randomUUID());
        }
        assertThatThrownBy(() -> client().campaignsAcross(PARTNER_TENANT, ACTOR_KEY, tooMany, null,
                null, 50))
                .isInstanceOf(UpstreamRejectedException.class)
                .hasMessageContaining("50");
    }

    private List<String> names(PagedResponse<Object> page) {
        return page.data().stream().map(row -> String.valueOf(asMap(row).get("name"))).toList();
    }

    private List<String> tenantIds(PagedResponse<Object> page) {
        return page.data().stream().map(row -> String.valueOf(asMap(row).get("tenantId"))).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object row) {
        return (Map<String, Object>) row;
    }
}
