package com.wasparks.api.enums;

/**
 * How a plan charges (epic §0.9, migration 021 §4).
 *
 * <p>{@code FIXED} is the P1 shape: an allowance per day and per month, with {@link OveragePolicy}
 * deciding what happens past it. {@code METERED} has no meaningful allowance — every accepted message is
 * counted and priced at {@code price_per_message_minor}, and the overage policy is irrelevant because
 * there is nothing to be over.
 *
 * <p>This service never invoices. The distinction exists here so the Partner console can say
 * "12,400 messages this month" rather than "12,400 of 20,000", which would be a lie on a metered plan.
 */
public enum BillingModel {
    FIXED,
    METERED
}
