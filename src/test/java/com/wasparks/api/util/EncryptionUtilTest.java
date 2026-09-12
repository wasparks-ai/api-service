package com.wasparks.api.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encryption contract shared with admin-service and tenants-service (shared-contracts §1).
 *
 * <p>This class is a byte-for-byte port, and these are the same assertions the other two services make.
 * They matter because the three services encrypt and decrypt <em>each other's</em> values: if this
 * implementation ever drifted, the symptom would not be a test failure here but a webhook secret that
 * cannot be decrypted in production.
 */
class EncryptionUtilTest {

    private EncryptionUtil encryptionUtil;

    @BeforeEach
    void setUp() {
        encryptionUtil = new EncryptionUtil();
        ReflectionTestUtils.setField(encryptionUtil, "secretKey", "test-encryption-key-32-chars!!");
        encryptionUtil.init();
    }

    @Test
    @DisplayName("a value survives an encrypt/decrypt round trip")
    void roundTrip() {
        String plain = "whsec_ZmFrZS1zZWNyZXQtZm9yLXRlc3Rpbmc";
        String encrypted = encryptionUtil.encrypt(plain);

        assertNotEquals(plain, encrypted, "the ciphertext must not be the plaintext");
        assertEquals(plain, encryptionUtil.decrypt(encrypted));
    }

    @Test
    @DisplayName("encrypting the same value twice gives different ciphertexts")
    void randomIv() {
        // A fresh IV per encryption. Without it, two endpoints that happened to share a secret would
        // have identical ciphertexts in the table, which leaks that they are the same.
        String plain = "same-secret";
        assertNotEquals(encryptionUtil.encrypt(plain), encryptionUtil.encrypt(plain));
    }

    @Test
    @DisplayName("decrypt fails fast on a value that was never encrypted")
    void failsFastOnPlaintext() {
        // The guard that exists because a raw token written straight to the database otherwise blows up
        // deep inside the cipher with an error that names nothing useful.
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> encryptionUtil.decrypt("EAABsomethingThatIsNotBase64!!"));
        assertTrue(error.getMessage().contains("not encrypted"),
                "the message must say the value was stored unencrypted, not just 'decryption failed'");
    }

    @Test
    @DisplayName("decrypt rejects a payload too short to hold an IV")
    void failsFastOnTruncated() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> encryptionUtil.decrypt("AAAA"));
        assertTrue(error.getMessage().contains("too short"));
    }

    @Test
    @DisplayName("null and empty pass through untouched")
    void passThrough() {
        assertNull(encryptionUtil.encrypt(null));
        assertNull(encryptionUtil.decrypt(null));
        assertEquals("", encryptionUtil.encrypt(""));
        assertEquals("", encryptionUtil.decrypt(""));
    }

    @Test
    @DisplayName("a key longer than 32 bytes is truncated to its first 32")
    void longKeyIsTruncated() {
        // The derivation rule, asserted directly: a 48-character secret and its own first 32 characters
        // must produce the same AES key. This is the rule admin-service and tenants-service implement,
        // and a divergence here would mean a value one service wrote could not be read by another —
        // with no error until a webhook secret failed to decrypt in production.
        String longSecret = "0123456789abcdef0123456789abcdefEXTRA-IGNORED-TA";
        String first32 = longSecret.substring(0, 32);

        EncryptionUtil withLong = build(longSecret);
        EncryptionUtil withFirst32 = build(first32);

        assertEquals("value", withFirst32.decrypt(withLong.encrypt("value")));
        assertEquals("value", withLong.decrypt(withFirst32.encrypt("value")));
    }

    @Test
    @DisplayName("a key shorter than 32 bytes is zero-padded, not rejected")
    void shortKeyIsPadded() {
        EncryptionUtil util = build("short-key");
        assertEquals("value", util.decrypt(util.encrypt("value")));
    }

    private EncryptionUtil build(String secret) {
        EncryptionUtil util = new EncryptionUtil();
        ReflectionTestUtils.setField(util, "secretKey", secret);
        util.init();
        return util;
    }
}
