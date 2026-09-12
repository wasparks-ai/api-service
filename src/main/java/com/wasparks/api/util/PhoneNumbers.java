package com.wasparks.api.util;

import java.util.regex.Pattern;

/**
 * E.164 shape checking for the LOCAL half of the send preflight (§B5). This is deliberately a shape
 * check and not a libphonenumber validation: the authoritative normalisation happens in tenants-service,
 * and rejecting a number here that Meta would have accepted is worse than passing one through.
 */
public final class PhoneNumbers {

    /** E.164: leading +, a non-zero country digit, then 7 to 14 more. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private PhoneNumbers() {
    }

    /** Accepts "+919876543210", "919876543210", "+91 98765 43210" — anything that normalises to E.164. */
    public static boolean isValid(String raw) {
        return raw != null && E164.matcher(normalize(raw)).matches();
    }

    /** Strips spaces, dashes, brackets and dots, and adds the leading + if the caller omitted it. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s\\-()./]", "");
        if (!digits.startsWith("+")) {
            digits = "+" + digits;
        }
        return digits;
    }

    /** Meta's {@code wa_id} is the E.164 number without the plus. */
    public static String toWaId(String e164) {
        String n = normalize(e164);
        return n == null ? null : n.substring(1);
    }
}
