package com.wasparks.api.enums;

/**
 * How a partner's client came to exist ({@code api_partner_tenants.created_via}, 021 §2): through the
 * partner's own integration, or by a human in the Partner console. Recorded because the two have
 * different support stories — an API-created client has an {@code externalRef} the partner can match
 * against its own database, and a console-created one usually does not.
 */
public enum CreatedVia {
    API,
    CONSOLE
}
