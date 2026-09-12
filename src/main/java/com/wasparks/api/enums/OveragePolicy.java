package com.wasparks.api.enums;

/** Plan attribute. BLOCK rejects over-quota sends with 429; WARN accepts them and flags the response. */
public enum OveragePolicy {
    BLOCK,
    WARN
}
