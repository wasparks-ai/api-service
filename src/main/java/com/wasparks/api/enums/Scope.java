package com.wasparks.api.enums;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The v1 scope vocabulary (api-ecosystem epic §B2, extended by api-partner epic §B1). A key's scopes are stored as a JSONB string array and surface as
 * Spring Security authorities with the {@code SCOPE_} prefix, so a controller gates on
 * {@code hasAuthority('SCOPE_messages:send')}.
 *
 * <p>The wire form is the colon string ({@code messages:send}), not the Java constant — these values are
 * part of the public contract and appear in every key-create request from tenant-web.
 *
 * <p>{@code conversations:read} and {@code contacts:write} are named in the epic as P2 and are
 * deliberately absent: a scope with no endpoint behind it is a promise this service cannot keep, and a
 * key minted with it today would silently widen the day those endpoints ship.
 */
public enum Scope {

    MESSAGES_SEND("messages:send"),
    MESSAGES_READ("messages:read"),
    TEMPLATES_READ("templates:read"),
    TEMPLATES_WRITE("templates:write"),
    WEBHOOKS_MANAGE("webhooks:manage"),
    ACCOUNT_READ("account:read"),

    // ---- api-partner epic §B1. Partner keys default to all of these, as every key does. ----
    CUSTOMERS_READ("customers:read"),
    CUSTOMERS_WRITE("customers:write"),
    CAMPAIGNS_READ("campaigns:read"),
    CAMPAIGNS_WRITE("campaigns:write"),
    MEDIA_READ("media:read");

    /** The Spring Security authority prefix for a scope. */
    public static final String AUTHORITY_PREFIX = "SCOPE_";

    private final String wire;

    Scope(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public String authority() {
        return AUTHORITY_PREFIX + wire;
    }

    /** Every scope — what a new key gets when the caller does not narrow it (epic §B2). */
    public static Set<String> allWire() {
        return Arrays.stream(values()).map(Scope::wire)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static Optional<Scope> fromWire(String value) {
        return Arrays.stream(values()).filter(s -> s.wire.equals(value)).findFirst();
    }
}
