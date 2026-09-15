package com.wasparks.api.auth;

import com.wasparks.api.meta.MetaMessagesController;
import com.wasparks.api.v1.AccountController;
import com.wasparks.api.v1.AudiencesController;
import com.wasparks.api.v1.CampaignsController;
import com.wasparks.api.v1.CustomersController;
import com.wasparks.api.v1.KeysController;
import com.wasparks.api.v1.MediaController;
import com.wasparks.api.v1.MessagesController;
import com.wasparks.api.v1.TemplatesController;
import com.wasparks.api.v1.UploadsController;
import com.wasparks.api.v1.WebhooksController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scope check exists in two places on purpose (see {@link RequiredScope}), and this is what stops
 * them drifting.
 *
 * <p>{@code @PreAuthorize} is the authoritative gate but runs after every interceptor;
 * {@code @RequiredScope} runs third in the pipeline so a mis-scoped request cannot consume an
 * idempotency key or a message quota first. A handler carrying one without the other has a real bug —
 * either a scope that is enforced too late, or one that is enforced but invisible to the pipeline — and
 * neither shows up in an ordinary functional test, because the endpoint still behaves correctly for a
 * caller who happens to have the scope.
 *
 * <p>This walks the annotations rather than exercising requests, because that is the only way to catch
 * the handler somebody adds next year and annotates once.
 */
class ScopeAnnotationContractTest {

    /** Every controller whose endpoints are authenticated by API key. */
    private static final List<Class<?>> KEY_AUTHENTICATED = List.of(
            MetaMessagesController.class,
            MessagesController.class,
            TemplatesController.class,
            AccountController.class,
            WebhooksController.class,
            // The partner platform's own key-authenticated surface. These were not covered when they
            // were added, which is its own small version of the same lesson: a contract test only
            // covers what it is told about.
            CustomersController.class,
            CampaignsController.class,
            AudiencesController.class,
            UploadsController.class,
            MediaController.class);

    @Test
    @DisplayName("every key-authenticated handler carries both annotations, agreeing on the scope")
    void bothAnnotationsAgree() {
        List<String> problems = new ArrayList<>();
        int handlers = 0;

        for (Class<?> controller : KEY_AUTHENTICATED) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
                    continue;
                }
                handlers++;

                RequiredScope required = method.getAnnotation(RequiredScope.class);
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                String where = controller.getSimpleName() + "." + method.getName();

                if (required == null) {
                    problems.add(where + " has no @RequiredScope — its scope would be checked only "
                            + "after idempotency and quota had already been consumed");
                    continue;
                }
                if (preAuthorize == null) {
                    problems.add(where + " has no @PreAuthorize — the authoritative gate is missing");
                    continue;
                }

                String expected = "hasAuthority('" + required.value().authority() + "')";
                if (!expected.equals(preAuthorize.value())) {
                    problems.add(where + " annotations disagree: @RequiredScope says "
                            + required.value().wire() + " but @PreAuthorize says "
                            + preAuthorize.value());
                }
            }
        }

        assertTrue(handlers > 0, "no handlers were inspected — the controller list is wrong");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("the keys controller carries neither annotation")
    void keysControllerIsJwtOnly() {
        // /v1/keys is authenticated by a tenant session, not by a key, so a scope gate there would
        // evaluate against authorities the session token does not carry and reject every caller.
        List<String> problems = new ArrayList<>();
        for (Method method : KeysController.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            if (method.isAnnotationPresent(RequiredScope.class)) {
                problems.add(method.getName() + " has @RequiredScope");
            }
            if (method.isAnnotationPresent(PreAuthorize.class)) {
                problems.add(method.getName() + " has @PreAuthorize");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
