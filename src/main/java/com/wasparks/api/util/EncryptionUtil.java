package com.wasparks.api.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption — a BYTE-FOR-BYTE port of the admin / tenants services' {@code EncryptionUtil}
 * (shared-contracts §1). Algorithm, key derivation (pad/truncate to 32 bytes), IV length, tag length and
 * the IV-then-ciphertext Base64 layout are IDENTICAL, so a value encrypted by any of the three services
 * decrypts in the other two. The key comes from {@code app.encryption.secret-key} (env
 * {@code ENCRYPTION_SECRET}), which MUST be the same value the other services run with.
 *
 * <p>Here it protects {@code api_webhook_endpoints.secret_encrypted} — the per-endpoint HMAC signing
 * secret, which is shown to the tenant once at creation and never again.
 *
 * <p><b>Never log decrypted secret values.</b>
 */
@Component
@Slf4j
public class EncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    @Value("${app.encryption.secret-key}")
    private String secretKey;

    private SecretKeySpec keySpec;
    private SecureRandom secureRandom;

    @PostConstruct
    public void init() {
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);

        // Ensure key is exactly 32 bytes (256 bits) for AES-256 — identical to admin.
        if (keyBytes.length < 32) {
            byte[] paddedKey = new byte[32];
            System.arraycopy(keyBytes, 0, paddedKey, 0, keyBytes.length);
            keyBytes = paddedKey;
        } else if (keyBytes.length > 32) {
            byte[] truncatedKey = new byte[32];
            System.arraycopy(keyBytes, 0, truncatedKey, 0, 32);
            keyBytes = truncatedKey;
        }

        this.keySpec = new SecretKeySpec(keyBytes, "AES");
        this.secureRandom = new SecureRandom();
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Combine IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("Error encrypting data", e);
            throw new RuntimeException("Error encrypting data", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }

        // Guard the Base64 decode BEFORE the AES-GCM step. A raw secret written straight to the DB
        // (never run through encrypt()) is not valid Base64 and would otherwise blow up deep inside
        // the cipher with a misleading error.
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encryptedText);
        } catch (IllegalArgumentException e) {
            log.error("Stored value is not valid Base64 — it was likely saved unencrypted");
            throw new RuntimeException("Stored value is not encrypted (invalid Base64). "
                    + "Re-save it through the encrypt() path.");
        }

        // Must be long enough to hold the 12-byte IV plus at least some ciphertext + tag.
        if (decoded.length <= GCM_IV_LENGTH) {
            throw new RuntimeException("Stored value is not a valid encrypted payload (too short).");
        }

        try {
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Error decrypting data", e);
            throw new RuntimeException("Error decrypting data", e);
        }
    }
}
