package com.atlas.roots.util;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Thin wrapper over jBCrypt that pins the work factor and centralises
 * the password ceremony in one place.
 *
 * <p>Cost factor 12 is the 2026 sweet spot: ~250ms on a modern laptop,
 * which is fast enough to feel instant on login and slow enough to
 * make brute-force expensive.</p>
 */
public final class BCryptHelper {

    private static final int WORK_FACTOR = 12;

    private BCryptHelper() {}

    public static String hash(String plaintext) {
        return BCrypt.hashpw(plaintext, BCrypt.gensalt(WORK_FACTOR));
    }

    public static boolean verify(String plaintext, String hash) {
        if (plaintext == null || hash == null || hash.isBlank()) return false;
        try {
            return BCrypt.checkpw(plaintext, hash);
        } catch (IllegalArgumentException e) {
            // Malformed hash — treat as non-match rather than crashing.
            return false;
        }
    }
}
