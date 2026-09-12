package com.wasparks.api.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event subscription matching. These patterns come from tenant input, so the two things that matter are
 * that the obvious cases work and that nothing in the input is treated as a regular expression.
 */
class WebhookEventsMatchTest {

    @Test
    @DisplayName("an exact name matches only itself")
    void exact() {
        assertTrue(WebhookEvents.matches("message.sent", "message.sent"));
        assertFalse(WebhookEvents.matches("message.sent", "message.failed"));
    }

    @Test
    @DisplayName("a trailing star matches the whole family")
    void prefixGlob() {
        assertTrue(WebhookEvents.matches("message.*", "message.sent"));
        assertTrue(WebhookEvents.matches("message.*", "message.delivered"));
        assertFalse(WebhookEvents.matches("message.*", "template.approved"));
    }

    @Test
    @DisplayName("a bare star matches everything")
    void everything() {
        assertTrue(WebhookEvents.matches("*", "message.sent"));
        assertTrue(WebhookEvents.matches("*", "ping"));
    }

    @Test
    @DisplayName("the dot is literal, not a regex wildcard")
    void dotIsLiteral() {
        // If patterns were compiled as regexes, "message.sent" would match "messageXsent" — and worse,
        // a tenant could paste a catastrophically backtracking pattern into the field and stall the
        // poller thread for every tenant.
        assertFalse(WebhookEvents.matches("message.sent", "messageXsent"));
    }

    @Test
    @DisplayName("regex metacharacters in a pattern are literal too")
    void metacharactersAreLiteral() {
        assertFalse(WebhookEvents.matches("message.(sent|failed)", "message.sent"));
        assertTrue(WebhookEvents.matches("message.(sent|failed)", "message.(sent|failed)"));
    }

    @Test
    @DisplayName("a star in the middle matches across the gap")
    void infixGlob() {
        assertTrue(WebhookEvents.matches("account.*expired", "account.token_expired"));
        assertFalse(WebhookEvents.matches("account.*expired", "account.token_expiring"));
    }

    @Test
    @DisplayName("nulls are refused rather than thrown on")
    void nulls() {
        assertFalse(WebhookEvents.matches(null, "message.sent"));
        assertFalse(WebhookEvents.matches("message.*", null));
    }
}
