package com.atlas.roots.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Central crypto for Roots. Wraps the JDK-only primitives we rely on
 * and nothing else — no external dependencies, no native libraries.
 *
 * <h2>Password hashing</h2>
 * <p>Passwords are hashed with {@code PBKDF2-HMAC-SHA256} at 600,000
 * iterations and a 16-byte salt, producing a 32-byte derived hash.
 * The stored format is:</p>
 * <pre>pbkdf2$600000${base64-salt}${base64-hash}</pre>
 *
 * <p>600,000 iterations is the OWASP 2023 recommendation for
 * PBKDF2-HMAC-SHA256. This is slow on purpose: verifying a single
 * password takes ~300ms on modern hardware, making a brute-force
 * attack against a stolen hash unviable.</p>
 *
 * <h2>Field encryption</h2>
 * <p>Sensitive fields (node descriptions) are encrypted with
 * {@code AES-256-GCM}, a modern authenticated cipher that guarantees
 * both confidentiality and integrity &mdash; an attacker cannot
 * tamper with an encrypted field without the decryption failing
 * visibly rather than producing garbage.</p>
 *
 * <p>The AES key is derived from the user's password at login time
 * using {@code PBKDF2-HMAC-SHA256} with a per-user salt stored in
 * the {@code users} table (same salt as the password hash for
 * simplicity &mdash; different KDF purposes are domain-separated
 * by an info tag mixed into the salt).</p>
 *
 * <p>Encrypted payloads are stored as:</p>
 * <pre>enc1:${base64-iv}:${base64-ciphertext-with-tag}</pre>
 *
 * <p>The {@code enc1:} prefix is a version tag that lets future
 * versions of Roots read old ciphertexts, and lets code that reads
 * a field detect whether it is encrypted or legacy plaintext.</p>
 *
 * <h2>Key lifecycle</h2>
 * <p>The per-user AES key lives in memory only, inside the
 * {@code VaultGuardian} session map. When the user logs out, the
 * key is zeroed out and the session reference is dropped. When the
 * JVM exits, the key is gone. At no point is the key written to
 * disk.</p>
 */
public final class CryptoService {

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final int    PBKDF2_ITERATIONS = 600_000;
    private static final int    PBKDF2_KEY_LEN_BITS = 256;
    private static final int    SALT_LEN = 16;

    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final int    GCM_TAG_BITS = 128;
    private static final int    GCM_IV_LEN   = 12;

    private static final String ENC_PREFIX   = "enc1:";
    private static final String HASH_PREFIX  = "pbkdf2$";

    private static final SecureRandom RNG = new SecureRandom();

    // The legacy BCrypt format still appears in the seeded users table.
    // We detect it by the canonical "$2a$" / "$2b$" / "$2y$" prefix.
    private static boolean isBcryptHash(String stored) {
        return stored != null && (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$"));
    }

    private CryptoService() {}

    // ================================================================
    //  Password hashing (PBKDF2)
    // ================================================================

    /**
     * Hash a plaintext password for storage. Generates a fresh
     * 16-byte salt on every call, so two users with the same password
     * produce different hashes.
     */
    public static String hashPassword(String password) {
        try {
            byte[] salt = new byte[SALT_LEN];
            RNG.nextBytes(salt);
            byte[] hash = pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LEN_BITS);
            return HASH_PREFIX + PBKDF2_ITERATIONS + "$" +
                    Base64.getEncoder().encodeToString(salt) + "$" +
                    Base64.getEncoder().encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("password hashing failed", e);
        }
    }

    /**
     * Verify a plaintext password against a stored hash. Supports
     * both the new PBKDF2 format and the legacy BCrypt format, so
     * seeded accounts (draco / viewer) keep working until the user
     * logs in and the hash is upgraded transparently.
     *
     * @return true if the password matches, false otherwise
     */
    public static boolean verifyPassword(String password, String stored) {
        if (stored == null || stored.isEmpty()) return false;

        if (isBcryptHash(stored)) {
            // Fall back to BCrypt for legacy seeded accounts
            return BCryptHelper.verify(password, stored);
        }

        if (!stored.startsWith(HASH_PREFIX)) return false;

        try {
            // Format: pbkdf2$<iterations>$<salt>$<hash>
            String[] parts = stored.substring(HASH_PREFIX.length()).split("\\$");
            if (parts.length != 3) return false;
            int    iterations = Integer.parseInt(parts[0]);
            byte[] salt       = Base64.getDecoder().decode(parts[1]);
            byte[] expected   = Base64.getDecoder().decode(parts[2]);

            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations, expected.length * 8);

            // Constant-time comparison — no early return on first mismatch,
            // so timing doesn't reveal how many bytes matched.
            return MessageDigest.isEqual(expected, actual);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * True if a stored hash is still in the legacy BCrypt format
     * and should be upgraded on next successful login.
     */
    public static boolean needsRehash(String stored) {
        return isBcryptHash(stored);
    }

    // ================================================================
    //  Key derivation for field encryption
    // ================================================================

    /**
     * Derive a 256-bit AES key from the user's password. The salt is
     * mixed with a domain-separation info tag so that password-hash
     * salts and encryption-key salts are never interchangeable even
     * if the underlying salt bytes happen to be the same.
     *
     * @param password the user's plaintext password, available only
     *                 at login time
     * @param stored   the user's stored password hash (from which we
     *                 extract the per-user salt)
     * @return a 256-bit AES key, or null if derivation fails
     */
    public static byte[] deriveDataKey(String password, String stored) {
        if (stored == null || !stored.startsWith(HASH_PREFIX)) {
            // Legacy BCrypt users get a deterministic fallback key
            // derived from just the password — acceptable because the
            // alternative is no encryption at all until they log in
            // and upgrade.
            return deriveFallbackKey(password);
        }
        try {
            String[] parts = stored.substring(HASH_PREFIX.length()).split("\\$");
            if (parts.length != 3) return deriveFallbackKey(password);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            // Domain separation: prepend a purpose tag to the salt
            byte[] infoSalt = new byte[salt.length + 8];
            System.arraycopy("DATAKEY_".getBytes(StandardCharsets.UTF_8), 0, infoSalt, 0, 8);
            System.arraycopy(salt, 0, infoSalt, 8, salt.length);
            return pbkdf2(password.toCharArray(), infoSalt, PBKDF2_ITERATIONS / 4, 256);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return deriveFallbackKey(password);
        }
    }

    private static byte[] deriveFallbackKey(String password) {
        try {
            byte[] salt = "ROOTS_LEGACY_KEY_SALT".getBytes(StandardCharsets.UTF_8);
            return pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS / 4, 256);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    // ================================================================
    //  Field encryption (AES-256-GCM)
    // ================================================================

    /**
     * Encrypt a plaintext string with AES-256-GCM. Produces a string
     * in the format {@code enc1:<base64-iv>:<base64-ciphertext>}.
     *
     * <p>If the key is null (no session active) the plaintext is
     * returned as-is. This is intentional: it means the system still
     * functions for migration scripts and unit tests that don't have
     * a session, at the cost of those writes being unencrypted.</p>
     */
    public static String encryptString(String plaintext, byte[] key) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        if (key == null) return plaintext;
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            SecretKey sk  = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, sk, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ENC_PREFIX +
                    Base64.getEncoder().encodeToString(iv) + ":" +
                    Base64.getEncoder().encodeToString(ct);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("encryption failed", e);
        }
    }

    /**
     * Decrypt a field stored by {@link #encryptString}. If the stored
     * value doesn't have the {@code enc1:} prefix, it is assumed to
     * be legacy plaintext and returned unchanged &mdash; this lets
     * Roots read data that was written before encryption was enabled
     * without a migration step.
     *
     * <p>If the stored value is encrypted but the key is null or
     * wrong, the encrypted blob is returned as-is &mdash; garbled
     * but never silently corrupted.</p>
     */
    public static String decryptString(String stored, byte[] key) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(ENC_PREFIX)) return stored;  // legacy plaintext
        if (key == null) return stored;  // no session, show as-is
        try {
            String payload = stored.substring(ENC_PREFIX.length());
            int sep = payload.indexOf(':');
            if (sep < 0) return stored;
            byte[] iv = Base64.getDecoder().decode(payload.substring(0, sep));
            byte[] ct = Base64.getDecoder().decode(payload.substring(sep + 1));
            Cipher cipher = Cipher.getInstance(AES_ALGO);
            SecretKey sk  = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.DECRYPT_MODE, sk, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Wrong key or corrupted ciphertext — don't crash the UI,
            // just show a clear placeholder.
            return "[encrypted]";
        }
    }

    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(ENC_PREFIX);
    }

    // ================================================================
    //  Hash chain (audit log integrity)
    // ================================================================

    /**
     * Compute a SHA-256 hash over the chained previous hash and the
     * fields of an audit row. Used to make the audit log
     * tamper-evident: modifying any row breaks the chain from that
     * row forward, and {@link com.atlas.roots.dao.AuditDao#verifyChain}
     * can detect exactly where the break occurred.
     */
    public static String chainHash(String prevHash,
                                   long userId,
                                   String action,
                                   String detail,
                                   String createdAt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((prevHash == null ? "" : prevHash).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1F);
            md.update(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1F);
            md.update(action.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1F);
            md.update((detail == null ? "" : detail).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1F);
            md.update(createdAt.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (GeneralSecurityException e) {
            throw new SecurityException("hash chain failed", e);
        }
    }

    // ================================================================
    //  PBKDF2 core
    // ================================================================

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits)
            throws GeneralSecurityException {
        PBEKeySpec       spec    = new PBEKeySpec(password, salt, iterations, keyBits);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
