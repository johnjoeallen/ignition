package net.dublinux.ignition.zone;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for a member's git password/PAT — but unlike {@link SecretCipher}
 * (one key for every {@code zone_secret} row), the key here is derived
 * per-user with PBKDF2 from their {@code app_user} id, so decrypting one
 * member's credentials never turns on the key for anyone else's, or for any
 * other kind of zone secret (bot token, deploy token, ...). Deterministic —
 * the same user id always re-derives the same key, so nothing about the key
 * itself needs to be stored; only {@link #decrypt} with that same id can read
 * a value {@link #encrypt} wrote.
 *
 * <p>The "secret" PBKDF2 stretches is the user's UUID, not a real password —
 * it's a stored, knowable value once you're looking at the database, not
 * confidential like a passphrase. The salt below is a fixed, public constant
 * for exactly that reason: it only needs to make the derivation
 * reproducible, not to add secrecy the UUID itself can't provide. The
 * iteration count still buys something real, though — it's what stops a
 * fast brute-force sweep of the small (128-bit, and often *guessable* —
 * sequential or otherwise low-entropy in some UUID versions) input space.
 */
@Component
public class UserSecretCipher {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final byte[] SALT = "ignition-git-secret-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretKeySpec deriveKey(UUID userId) {
        try {
            PBEKeySpec spec = new PBEKeySpec(userId.toString().toCharArray(), SALT, PBKDF2_ITERATIONS, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("deriving per-user secret key", e);
        }
    }

    public String encrypt(String plaintext, UUID userId) {
        try {
            SecretKeySpec key = deriveKey(userId);
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
            throw new IllegalStateException("encrypting user secret", e);
        }
    }

    public String decrypt(String encoded, UUID userId) {
        try {
            SecretKeySpec key = deriveKey(userId);
            byte[] in = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(in, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(in, IV_LEN, in.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypting user secret", e);
        }
    }
}
