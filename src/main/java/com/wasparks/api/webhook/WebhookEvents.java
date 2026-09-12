package com.wasparks.api.webhook;

/**
 * The v1 event vocabulary (epic §B8). Most of these are written by tenants-service into the outbox;
 * the four marked below are written by this service, because they describe things only this service
 * knows about.
 */
public final class WebhookEvents {

    // Written by tenants-service (epic §C2).
    public static final String MESSAGE_SENT = "message.sent";
    public static final String MESSAGE_DELIVERED = "message.delivered";
    public static final String MESSAGE_READ = "message.read";
    public static final String MESSAGE_FAILED = "message.failed";
    public static final String TEMPLATE_APPROVED = "template.approved";
    public static final String TEMPLATE_REJECTED = "template.rejected";
    public static final String TEMPLATE_PAUSED = "template.paused";
    public static final String ACCOUNT_TOKEN_EXPIRING = "account.token_expiring";
    public static final String ACCOUNT_TOKEN_EXPIRED = "account.token_expired";
    public static final String ACCOUNT_QUALITY_CHANGED = "account.quality_changed";

    /** Written here: the tenant went past a plan quota on a WARN plan (epic §B3), once per day. */
    public static final String QUOTA_EXCEEDED = "quota.exceeded";
    /** Written here: {@code POST /v1/webhooks/{id}/test}. */
    public static final String PING = "ping";

    /** Aggregate types, matching the {@code aggregate_type} column (020). */
    public static final String AGGREGATE_MESSAGE = "message";
    public static final String AGGREGATE_TEMPLATE = "template";
    public static final String AGGREGATE_ACCOUNT = "account";
    public static final String AGGREGATE_WEBHOOK = "webhook";
    public static final String AGGREGATE_TENANT = "tenant";

    private WebhookEvents() {
    }

    /**
     * Glob match of an event type against an endpoint's subscription pattern.
     *
     * <p>Only {@code *} is supported, and it stands for "any run of characters" — {@code message.*}
     * matches every message event, {@code *} matches everything. Deliberately not a regular expression:
     * these patterns come from tenant input in tenant-web, and letting a customer paste a regex into a
     * field that runs on our poller thread is a denial-of-service waiting to happen.
     */
    public static boolean matches(String pattern, String eventType) {
        if (pattern == null || eventType == null) {
            return false;
        }
        String trimmed = pattern.trim();
        if (trimmed.equals("*")) {
            return true;
        }
        if (!trimmed.contains("*")) {
            return trimmed.equals(eventType);
        }
        StringBuilder regex = new StringBuilder();
        for (String part : trimmed.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(java.util.regex.Pattern.quote(part));
        }
        return eventType.matches(regex.toString());
    }
}
