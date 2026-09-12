package com.wasparks.api.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signs an outbound webhook so the receiver can prove it came from us (epic §B8).
 *
 * <p>Header: {@code X-WaSparks-Signature: t=<unix>,v1=<hex hmac-sha256(secret, t + "." + rawBody)>}.
 *
 * <p><b>The timestamp is inside the signed material, not beside it.</b> Signing the body alone would let
 * anyone who captured one delivery replay it forever — a {@code message.delivered} for an order that was
 * later cancelled, replayed at will. With the timestamp signed, a receiver that rejects anything older
 * than five minutes (our documented guidance) closes that window, and an attacker cannot move the
 * timestamp without invalidating the signature.
 *
 * <p>The scheme is deliberately Stripe's. Most engineers integrating a webhook have verified a Stripe
 * one, and a familiar format is a format that gets verified rather than skipped.
 *
 * <p>The signature covers the <b>exact bytes sent</b>, not a re-serialisation of the payload — a
 * receiver that re-encodes the JSON before verifying would compute a different digest, so the dispatcher
 * signs and transmits the same string.
 */
@Component
public class WebhookSigner {

    public static final String HEADER_SIGNATURE = "X-WaSparks-Signature";
    public static final String HEADER_EVENT = "X-WaSparks-Event";
    public static final String HEADER_DELIVERY = "X-WaSparks-Delivery";

    private static final String ALGORITHM = "HmacSHA256";

    /** Sign with the current time. */
    public String sign(String secret, String rawBody) {
        return sign(secret, rawBody, Instant.now().getEpochSecond());
    }

    /** Sign at a given epoch second — the seam the round-trip test verifies against. */
    public String sign(String secret, String rawBody, long timestamp) {
        String signed = timestamp + "." + rawBody;
        return "t=" + timestamp + ",v1=" + hmacHex(secret, signed);
    }

    /**
     * Verify a header, the way a customer's receiver would. Present so the round-trip is testable from
     * the same code path a customer follows, rather than reimplemented in the test and proved against
     * itself.
     *
     * @param toleranceSeconds reject a timestamp further than this from now; the documented guidance is
     *                         300 seconds
     */
    public boolean verify(String secret, String rawBody, String header, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        String timestampPart = null;
        String signaturePart = null;
        for (String segment : header.split(",")) {
            String trimmed = segment.trim();
            if (trimmed.startsWith("t=")) {
                timestampPart = trimmed.substring(2);
            } else if (trimmed.startsWith("v1=")) {
                signaturePart = trimmed.substring(3);
            }
        }
        if (timestampPart == null || signaturePart == null) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampPart);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > toleranceSeconds) {
            return false;
        }

        String expected = hmacHex(secret, timestamp + "." + rawBody);
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signaturePart.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacHex(String secret, String material) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign webhook payload", e);
        }
    }
}
