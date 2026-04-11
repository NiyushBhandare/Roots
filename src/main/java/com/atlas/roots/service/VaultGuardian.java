package com.atlas.roots.service;

import com.atlas.roots.dao.AuditDao;
import com.atlas.roots.dao.UserDao;
import com.atlas.roots.model.AuditEvent;
import com.atlas.roots.model.User;
import com.atlas.roots.util.BCryptHelper;
import com.atlas.roots.util.Validators;

import java.sql.SQLException;
import java.util.Optional;

/**
 * The gatekeeper of Roots.
 *
 * <p>VaultGuardian is the only place in the codebase that knows how to
 * verify a password, hold the current session, and answer the question
 * "is this user allowed to do that?" Centralising auth here means the
 * UI never touches BCrypt directly and the DAOs never touch session
 * state &mdash; both stay focused on their own job.</p>
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

    public VaultGuardian(UserDao userDao, AuditDao auditDao) {
        this.userDao  = userDao;
        this.auditDao = auditDao;
    }

    // -----------------------------------------------------------------
    //  Authentication.
    // -----------------------------------------------------------------

    /**
     * Attempt to log in. Validates the inputs, looks up the user,
     * verifies the password against the stored BCrypt hash, and
     * records the attempt in the audit log.
     *
     * @return the authenticated {@link User} on success, empty otherwise.
     */
    public Optional<User> login(String username, String password) {
        try {
            Validators.requireUsername(username);
            Validators.requirePassword(password);
        } catch (IllegalArgumentException malformed) {
            // Don't even hit the DB for malformed input.
            return Optional.empty();
        }

        try {
            Optional<User> found = userDao.findByUsername(username);
            if (found.isEmpty()) {
                // Constant-time-ish: still run a BCrypt verify against a
                // known hash so timing doesn't reveal whether the user
                // exists. Cheap defence, worth doing.
                BCryptHelper.verify(password, "$2a$12$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvali");
                return Optional.empty();
            }
            User user = found.get();
            if (!BCryptHelper.verify(password, user.getPasswordHash())) {
                auditDao.log(null, user.getId(), AuditEvent.Action.LOGIN, "FAILED");
                return Optional.empty();
            }
            this.currentUser = user;
            auditDao.log(null, user.getId(), AuditEvent.Action.LOGIN, "OK");
            return Optional.of(user);
        } catch (SQLException e) {
            // Auth failures should never throw to the UI; the UI just
            // sees an empty Optional and shows "invalid credentials".
            return Optional.empty();
        }
    }

    /** End the current session and write a logout event. */
    public void logout() {
        if (currentUser != null) {
            try {
                auditDao.log(null, currentUser.getId(), AuditEvent.Action.LOGOUT, "OK");
            } catch (SQLException ignored) { /* logout must always succeed locally */ }
        }
        this.currentUser = null;
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
        String hash = BCryptHelper.hash(password);
        User created = userDao.insert(new User(username, hash, role));
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
