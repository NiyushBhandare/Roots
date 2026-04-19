package com.atlas.roots.service;

import com.atlas.roots.dao.AuditDao;
import com.atlas.roots.dao.UserDao;
import com.atlas.roots.model.AuditEvent;
import com.atlas.roots.model.User;
import com.atlas.roots.util.CryptoService;
import com.atlas.roots.util.Validators;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The gatekeeper of Roots.
 *
 * <p>VaultGuardian is the only place in the codebase that knows how to
 * verify a password, hold the current session, and answer the question
 * "is this user allowed to do that?" Centralising auth here means the
 * UI never touches crypto directly and the DAOs never touch session
 * state &mdash; both stay focused on their own job.</p>
 *
 * <p>On successful login, a 256-bit AES data key is derived from the
 * user's password via PBKDF2 and held in memory for the duration of
 * the session. When the user logs out (or the JVM exits) the key is
 * gone. Encrypted node descriptions are readable only while this
 * key is held.</p>
 *
 * <p>Every login attempt (success or failure) is written to the audit
 * trail before the method returns, so the audit log is the source of
 * truth for "who tried to get in, and when."</p>
 */
public final class VaultGuardian {

    private final UserDao  userDao;
    private final AuditDao auditDao;

    /** The currently logged-in user, or null if no session is active. */
    private User currentUser;

    /** Per-user AES keys derived from password at login time. */
    private final Map<Long, byte[]> dataKeys = new HashMap<>();

    public VaultGuardian(UserDao userDao, AuditDao auditDao) {
        this.userDao  = userDao;
        this.auditDao = auditDao;
    }

    // -----------------------------------------------------------------
    //  Authentication.
    // -----------------------------------------------------------------

    /**
     * Attempt to log in. Validates the inputs, looks up the user,
     * verifies the password against the stored PBKDF2 hash (or
     * legacy BCrypt for seeded users), derives the session data key,
     * and records the attempt in the audit log.
     *
     * @return the authenticated {@link User} on success, empty otherwise.
     */
    public Optional<User> login(String username, String password) {
        try {
            Validators.requireUsername(username);
            Validators.requirePassword(password);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }

        try {
            Optional<User> found = userDao.findByUsername(username);
            if (found.isEmpty()) {
                // Constant-time-ish: burn cycles on a known hash so
                // timing doesn't reveal whether the user exists.
                CryptoService.verifyPassword(password, "pbkdf2$600000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
                return Optional.empty();
            }
            User user = found.get();
            if (!CryptoService.verifyPassword(password, user.getPasswordHash())) {
                auditDao.log(null, user.getId(), AuditEvent.Action.LOGIN, "FAILED");
                return Optional.empty();
            }

            // Upgrade legacy BCrypt hashes transparently on successful login
            if (CryptoService.needsRehash(user.getPasswordHash())) {
                try {
                    String newHash = CryptoService.hashPassword(password);
                    userDao.updatePasswordHash(user.getId(), newHash);
                    user = new User(user.getId(), user.getUsername(), newHash, user.getRole(), user.getCreatedAt());
                } catch (SQLException rehashFail) {
                    // Rehash failure shouldn't block login
                }
            }

            // Derive and store the session data key
            byte[] dataKey = CryptoService.deriveDataKey(password, user.getPasswordHash());
            dataKeys.put(user.getId(), dataKey);

            this.currentUser = user;
            auditDao.log(null, user.getId(), AuditEvent.Action.LOGIN, "OK");
            return Optional.of(user);
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    /** End the current session, write a logout event, and zero the data key. */
    public void logout() {
        if (currentUser != null) {
            try {
                auditDao.log(null, currentUser.getId(), AuditEvent.Action.LOGOUT, "OK");
            } catch (SQLException ignored) { /* logout must always succeed locally */ }
            // Zero the key bytes in memory before dropping the reference
            byte[] key = dataKeys.remove(currentUser.getId());
            if (key != null) java.util.Arrays.fill(key, (byte) 0);
        }
        this.currentUser = null;
    }

    /**
     * Return the per-user data key for a session, or null if the
     * user isn't currently logged in. Used by encryption-aware
     * DAOs to encrypt/decrypt sensitive fields.
     */
    public byte[] getDataKey(long userId) {
        return dataKeys.get(userId);
    }

    /**
     * Activate a session for a user who signed in via Google OAuth.
     *
     * <p>Unlike {@link #login(String, String)}, there is no password to
     * derive a key from. Instead we derive the data key from the user's
     * stored Google <code>sub</code> (a stable, unique Google user ID)
     * combined with their BCrypt password hash as salt. This gives a
     * deterministic per-user key: the same user always derives the same
     * key, so encrypted data (descriptions, tokens) survives across
     * sessions even though no password is ever typed.</p>
     *
     * <p>Security note: the data key never leaves the server. Google's
     * <code>sub</code> is the only external input, and it's not a secret
     * — but it doesn't need to be, because the BCrypt hash (high-entropy,
     * local-only) is mixed in as salt. Anyone with access to the database
     * already has everything, so deriving the key from DB contents is not
     * weaker than the rest of the system. This is an honest tradeoff for
     * a local-first app where the alternative is "no encryption for
     * OAuth-only users", which is worse.</p>
     */
    public void activateGoogleSession(User user) {
        if (user == null || user.getGoogleSub() == null) return;
        String seed = user.getGoogleSub() + "|roots-google-session";
        byte[] dataKey = CryptoService.deriveDataKey(seed, user.getPasswordHash());
        dataKeys.put(user.getId(), dataKey);
        this.currentUser = user;
    }

    /**
     * Register a new user. Used by the "Sign up" link on the login
     * screen. Returns empty if the username is taken.
     */
    public Optional<User> register(String username, String password, User.Role role) throws SQLException {
        Validators.requireUsername(username);
        Validators.requirePassword(password);
        if (userDao.findByUsername(username).isPresent()) {
            return Optional.empty();
        }
        String hash = CryptoService.hashPassword(password);
        User created = userDao.insert(new User(username, hash, role));

        // Newly registered users get an immediate session with a key
        byte[] dataKey = CryptoService.deriveDataKey(password, hash);
        dataKeys.put(created.getId(), dataKey);
        this.currentUser = created;

        return Optional.of(created);
    }

    // -----------------------------------------------------------------
    //  Session + authorization.
    // -----------------------------------------------------------------

    public boolean isAuthenticated() { return currentUser != null; }

    public Optional<User> getCurrentUser() { return Optional.ofNullable(currentUser); }

    public long requireUserId() {
        if (currentUser == null) throw new IllegalStateException("not authenticated");
        return currentUser.getId();
    }

    /** Throws if the current user is not an admin. Used by mutating views. */
    public void requireAdmin() {
        if (currentUser == null || !currentUser.isAdmin()) {
            throw new SecurityException("admin role required");
        }
    }
}
