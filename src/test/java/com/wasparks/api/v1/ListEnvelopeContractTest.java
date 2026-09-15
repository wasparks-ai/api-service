package com.wasparks.api.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every collection read on {@code /v1} answers in the house envelope (epic §B7:
 * {@code {"data":[…],"meta":{…}}}), and this is what stops one of them quietly answering in somebody
 * else's.
 *
 * <p>The bug it exists for was real and was invisible to every functional test that had it. The Partner
 * console's campaign list returned the envelope when it merged across customers and returned
 * tenants-service's raw Spring {@code Page} — {@code {content, number, size, totalElements}} — when the
 * caller narrowed to one. One endpoint, one caller, two shapes, decided by a query parameter. A test that
 * asserted on {@code $.content[0].id} passed; so did one that asserted on {@code $.data[0].id}; and the
 * client that had to handle both found out in production.
 *
 * <p>Five more list handlers had the same leak, for the same reason: a proxying controller that returns
 * {@code JsonNode} is returning whatever upstream said, and for a collection that is upstream's pagination
 * contract rather than ours. So the rule is structural, not behavioural — a collection read must
 * <b>declare</b> {@link PagedResponse}, which is a thing a compiler can check and a reviewer can see.
 *
 * <h2>How a collection read is recognised</h2>
 * A {@code @GetMapping} whose path does not end in a path variable. {@code /v1/campaigns} and
 * {@code /v1/campaigns/{id}/recipients} are collections; {@code /v1/campaigns/{id}} and
 * {@code /v1/media/{messageId}} are single resources, where upstream's object <em>is</em> the resource
 * and there is no envelope to leak.
 *
 * <p>That rule over-fires on a handful of <b>singleton</b> resources — {@code /v1/account} is one
 * composite object, not a list of anything — so those are named below, each with the reason it is not a
 * collection. The list is short, stable, and has to be added to deliberately, which is the point: the
 * next person who adds a raw pass-through has to justify it here rather than discover it later.
 */
class ListEnvelopeContractTest {

    /** Every controller serving {@code /v1}. A new one must be added here or it is not covered. */
    private static final List<Class<?>> V1_CONTROLLERS = List.of(
            AccountController.class,
            AudiencesController.class,
            CampaignsController.class,
            CustomersController.class,
            KeysController.class,
            MediaController.class,
            MessagesController.class,
            PartnerConsoleController.class,
            TemplatesController.class,
            UploadsController.class,
            WebhooksController.class);

    /**
     * Handlers the path rule calls collections and that are not.
     *
     * <p>Each is a single composite resource served at a collection-shaped path. Adding to this set is
     * how an exception gets made; the reason belongs beside it.
     */
    private static final Set<String> SINGLETON_RESOURCES = Set.of(
            // The caller's own account: numbers, plan and usage in one object.
            "AccountController#get",
            // The caller's own partner record, plan and pool meters.
            "PartnerConsoleController#me",
            // A usage report for a month — totals plus a per-customer breakdown, not a page of rows.
            "PartnerConsoleController#usage",
            // The fixed event vocabulary the picker is built from. It has no pages and never will.
            "WebhooksController#events");

    /**
     * The one collection read that is allowed to return upstream's shape, because changing it now would
     * break clients that are already using it.
     *
     * <p>{@code GET /v1/templates} shipped in P1 and has been live since 2026-09-15 returning
     * tenants-service's paged shape. It has the same leak as the five this change fixed, and unlike them
     * it is not a new endpoint — normalising it is a breaking change to a public contract and therefore
     * somebody's decision, not a tidy-up. It is named here so the exception is visible rather than
     * absent, and so that this test goes green on the day it is fixed by deleting one line.
     */
    private static final Set<String> SHIPPED_BEFORE_THE_ENVELOPE_RULE = Set.of(
            "TemplatesController#list");

    @Test
    @DisplayName("every collection read on /v1 declares the PagedResponse envelope")
    void collectionsReturnTheEnvelope() {
        List<String> problems = new ArrayList<>();
        int collections = 0;

        for (Class<?> controller : V1_CONTROLLERS) {
            for (Method method : handlers(controller)) {
                GetMapping get = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
                if (get == null || !isCollectionPath(get)) {
                    continue;
                }
                String name = controller.getSimpleName() + "#" + method.getName();
                if (SINGLETON_RESOURCES.contains(name)
                        || SHIPPED_BEFORE_THE_ENVELOPE_RULE.contains(name)) {
                    continue;
                }
                collections++;

                if (!PagedResponse.class.isAssignableFrom(method.getReturnType())) {
                    problems.add(name + " returns " + method.getReturnType().getSimpleName()
                            + " — a collection read must return PagedResponse so it answers in the "
                            + "house {data, meta} envelope rather than upstream's shape. If it is "
                            + "really a single resource, name it in SINGLETON_RESOURCES with a reason.");
                }
            }
        }

        assertTrue(collections >= 10,
                "the walk found only " + collections + " collection reads, which means the rule stopped "
                        + "matching rather than that the leaks were fixed");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("no collection read hands a raw JsonNode back to the caller")
    void collectionsDoNotProxyRawJson() {
        List<String> problems = new ArrayList<>();

        for (Class<?> controller : V1_CONTROLLERS) {
            for (Method method : handlers(controller)) {
                GetMapping get = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
                if (get == null || !isCollectionPath(get)) {
                    continue;
                }
                String name = controller.getSimpleName() + "#" + method.getName();
                if (SINGLETON_RESOURCES.contains(name)
                        || SHIPPED_BEFORE_THE_ENVELOPE_RULE.contains(name)) {
                    continue;
                }
                // Stated separately from the check above because it is the failure that actually
                // reaches a client: a JsonNode return is upstream's body, verbatim, pagination included.
                if (com.fasterxml.jackson.databind.JsonNode.class
                        .isAssignableFrom(method.getReturnType())) {
                    problems.add(name + " proxies upstream's JSON straight through");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("the exemption lists name handlers that still exist")
    void exemptionsAreReal() {
        List<String> known = new ArrayList<>();
        for (Class<?> controller : V1_CONTROLLERS) {
            for (Method method : handlers(controller)) {
                known.add(controller.getSimpleName() + "#" + method.getName());
            }
        }
        // An exemption for a handler that has been renamed or deleted is an exemption nobody is
        // watching — and the next handler to take that name inherits it silently.
        for (String exempt : SINGLETON_RESOURCES) {
            assertTrue(known.contains(exempt), "stale exemption: " + exempt);
        }
        for (String exempt : SHIPPED_BEFORE_THE_ENVELOPE_RULE) {
            assertTrue(known.contains(exempt), "stale exemption: " + exempt);
        }
        assertFalse(known.isEmpty());
    }

    private static List<Method> handlers(Class<?> controller) {
        List<Method> handlers = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) != null) {
                handlers.add(method);
            }
        }
        return handlers;
    }

    /**
     * A GET is a collection read unless its path ends in a path variable.
     *
     * <p>{@code ""} (the class-level mapping alone) and {@code "/{id}/recipients"} are collections;
     * {@code "/{id}"} is one resource. Crude, and right for every route this service has — a route it
     * gets wrong is a route that should be named in one of the exemption sets above.
     */
    private static boolean isCollectionPath(GetMapping get) {
        String[] paths = get.value().length > 0 ? get.value() : get.path();
        if (paths.length == 0) {
            return true;
        }
        for (String path : paths) {
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            String last = trimmed.contains("/")
                    ? trimmed.substring(trimmed.lastIndexOf('/') + 1)
                    : trimmed;
            if (!(last.startsWith("{") && last.endsWith("}"))) {
                return true;
            }
        }
        return false;
    }
}
