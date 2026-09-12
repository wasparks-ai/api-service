package com.wasparks.api.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The webhook signature contract (epic §B8). What is asserted here is exactly what a customer's
 * receiver will implement against, so each case is a promise the documentation makes.
 */
class WebhookSignerTest {

    private static final String SECRET = "whsec_test_secret_value";
    private static final String BODY =
            "{\"id\":\"evt_1\",\"type\":\"message.sent\",\"data\":{\"messageId\":\"abc\"}}";

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    @DisplayName("a signature we produce verifies with the same secret and body")
    void roundTrip() {
        String header = signer.sign(SECRET, BODY);
        assertTrue(signer.verify(SECRET, BODY, header, 300));
    }

    @Test
    @DisplayName("the header carries both t and v1")
    void headerShape() {
        String header = signer.sign(SECRET, BODY, 1757000000L);
        assertTrue(header.startsWith("t=1757000000,v1="), "got: " + header);
        assertTrue(header.substring(header.indexOf("v1=") + 3).matches("[0-9a-f]{64}"),
                "v1 must be a hex SHA-256 digest");
    }

    @Test
    @DisplayName("a tampered body fails verification")
    void tamperedBody() {
        String header = signer.sign(SECRET, BODY);
        String tampered = BODY.replace("message.sent", "message.failed");
        assertFalse(signer.verify(SECRET, tampered, header, 300));
    }

    @Test
    @DisplayName("the wrong secret fails verification")
    void wrongSecret() {
        String header = signer.sign(SECRET, BODY);
        assertFalse(signer.verify("whsec_a_different_secret", BODY, header, 300));
    }

    @Test
    @DisplayName("an old timestamp is rejected even though the digest is intact")
    void replayIsRejected() {
        // The replay guard. The signature over an hour-old delivery is still cryptographically valid —
        // only the timestamp check stops it being replayed, which is why the timestamp is signed.
        long anHourAgo = Instant.now().getEpochSecond() - 3600;
        String header = signer.sign(SECRET, BODY, anHourAgo);

        assertFalse(signer.verify(SECRET, BODY, header, 300), "outside the 5-minute tolerance");
        assertTrue(signer.verify(SECRET, BODY, header, 7200), "inside a wider tolerance");
    }

    @Test
    @DisplayName("moving the timestamp invalidates the signature")
    void timestampIsSigned() {
        String header = signer.sign(SECRET, BODY, Instant.now().getEpochSecond() - 3600);
        String movedForward = header.replaceFirst("t=\\d+", "t=" + Instant.now().getEpochSecond());
        assertFalse(signer.verify(SECRET, BODY, movedForward, 300),
                "an attacker must not be able to refresh the timestamp on a captured delivery");
    }

    @Test
    @DisplayName("a malformed or absent header is refused rather than throwing")
    void malformedHeader() {
        assertFalse(signer.verify(SECRET, BODY, null, 300));
        assertFalse(signer.verify(SECRET, BODY, "", 300));
        assertFalse(signer.verify(SECRET, BODY, "v1=deadbeef", 300));
        assertFalse(signer.verify(SECRET, BODY, "t=notanumber,v1=deadbeef", 300));
    }
}
