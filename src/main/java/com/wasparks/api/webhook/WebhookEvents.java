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

    // Written by tenants-service (api-partner epic §C1, §C3). New in P2/P3.
    /** Every inbound message, with its media already copied into our own bucket (§B4). */
    public static final String MESSAGE_RECEIVED = "message.received";
    public static final String CAMPAIGN_SCHEDULED = "campaign.scheduled";
    public static final String CAMPAIGN_STARTED = "campaign.started";
    public static final String CAMPAIGN_PAUSED = "campaign.paused";
    public static final String CAMPAIGN_RESUMED = "campaign.resumed";
    public static final String CAMPAIGN_COMPLETED = "campaign.completed";
    public static final String CAMPAIGN_CANCELLED = "campaign.cancelled";
    /** A customer's number was connected, by setup link or by direct mapping. */
    public static final String CUSTOMER_CONNECTED = "customer.connected";
    /** A partner-owned customer's token expired — emitted only for partner-owned tenants (§0.7). */
    public static final String CUSTOMER_DISCONNECTED = "customer.disconnected";
    public static final String SETUP_LINK_EXPIRED = "setup_link.expired";

    /** Written here: the tenant went past a plan quota on a WARN plan (epic §B3), once per day. */
    public static final String QUOTA_EXCEEDED = "quota.exceeded";
    /** Written here: {@code POST /v1/webhooks/{id}/test}. */
    public static final String PING = "ping";

    /**
     * {@code campaign.paused} reasons. {@code QUOTA} is this service's own (api-partner §B3): the other
     * three are decided by the campaign engine, while a campaign stopped for running out of the
     * partner's allowance is stopped by a rule that only lives here.
     */
    public static final String PAUSE_REASON_QUOTA = "QUOTA";

    /** Aggregate types, matching the {@code aggregate_type} column (020/021). */
    public static final String AGGREGATE_MESSAGE = "message";
    public static final String AGGREGATE_TEMPLATE = "template";
    public static final String AGGREGATE_ACCOUNT = "account";
    public static final String AGGREGATE_WEBHOOK = "webhook";
    public static final String AGGREGATE_TENANT = "tenant";
    public static final String AGGREGATE_CAMPAIGN = "campaign";
    public static final String AGGREGATE_SETUP_LINK = "setup_link";

    /**
     * Every event a webhook endpoint may subscribe to, in the order the picker shows them (§B5).
     *
     * <p>Kept as a list rather than derived by reflection so that the order is deliberate and so that an
     * event this service knows about but does not offer — there are none today — would have to be left
     * out on purpose rather than by an accident of field ordering.
     */
    public static final java.util.List<String> SUBSCRIBABLE = java.util.List.of(
            MESSAGE_SENT, MESSAGE_DELIVERED, MESSAGE_READ, MESSAGE_FAILED, MESSAGE_RECEIVED,
            TEMPLATE_APPROVED, TEMPLATE_REJECTED, TEMPLATE_PAUSED,
            CAMPAIGN_SCHEDULED, CAMPAIGN_STARTED, CAMPAIGN_PAUSED, CAMPAIGN_RESUMED,
            CAMPAIGN_COMPLETED, CAMPAIGN_CANCELLED,
            CUSTOMER_CONNECTED, CUSTOMER_DISCONNECTED, SETUP_LINK_EXPIRED,
            ACCOUNT_TOKEN_EXPIRING, ACCOUNT_TOKEN_EXPIRED, ACCOUNT_QUALITY_CHANGED,
            QUOTA_EXCEEDED, PING);

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
