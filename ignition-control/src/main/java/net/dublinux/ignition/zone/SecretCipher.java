package net.dublinux.ignition.zone;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import net.dublinux.ignition.config.IgnitionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for the {@code zone_secret} values, so a {@code pg_dump} isn't a
 * plaintext credential dump. Key is {@code ignition.secret-key} (32 bytes,
 * base64). Encoded value is {@code base64(iv[12] || ciphertext||tag)}.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(IgnitionProperties props) {
        String raw = props.getSecretKey();
        byte[] bytes;
        if (raw == null || raw.isBlank()) {
            log.warn("ignition.secret-key not set — using a fixed development key. "
                    + "Set IGN_SECRET_KEY (32 bytes, base64) before storing real credentials.");
            bytes = "ignition-dev-key-not-for-production!".substring(0, 32).getBytes(StandardCharsets.US_ASCII);
        } else {
            bytes = Base64.getDecoder().decode(raw.strip());
            if (bytes.length != 32) {
                throw new IllegalStateException("ignition.secret-key must decode to 32 bytes, got " + bytes.length);
            }
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("encrypting secret", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(in, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(in, IV_LEN, in.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypting secret", e);
        }
    }
}
