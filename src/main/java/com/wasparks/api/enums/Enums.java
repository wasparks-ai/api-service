package com.wasparks.api.enums;

/**
 * Marker/holder for the package javadoc. The enums themselves are one file each, mirroring the
 * {@code VARCHAR} + {@code CHECK} constraints in migration 020 — none of the new API tables use a
 * PostgreSQL enum type (epic §A: "VARCHAR everywhere a Java String is mapped").
 *
 * <p>The two exceptions are the pre-existing shared tables this service only READS:
 * {@code tenants.status} and {@code whatsapp_accounts.status} are real PG enum types and are mapped
 * with {@code PostgreSQLEnumJdbcType} exactly as tenants-service maps them.
 */
public final class Enums {

    private Enums() {
    }
}
