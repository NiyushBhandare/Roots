package com.atlas.roots.dao;

import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.User;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * DAO for {@link User} entities. Lives outside the {@link NodeRepository}
 * hierarchy because users are not nodes — they own them.
 */
public class UserDao {

    private static final DateTimeFormatter SQLITE_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatabaseManager db;

    public UserDao(DatabaseManager db) {
        this.db = db;
    }

    public User insert(User user) throws SQLException {
        String sql = """
                INSERT INTO users (username, password_hash, role, created_at)
                VALUES (?, ?, ?, ?)""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getRole().name());
            ps.setString(4, user.getCreatedAt().format(SQLITE_DT));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) user.setId(keys.getLong(1));
            }
            return user;
        }
    }

    /**
     * Atomically create a Roots user from a Google sign-in and link the
     * Google account in a single INSERT. Used when a Gmail signs in via
     * OAuth and no existing Roots user matches.
     *
     * <p>The password hash is required (the schema mandates NOT NULL) so
     * we store a random high-entropy hash — it's never used for login
     * because the user will always sign in via Google. But it does double
     * duty as the symmetric-encryption key source for that user's data,
     * so it must be persisted and re-used deterministically across sessions.</p>
     */
    public User createFromGoogle(String username, String bcryptHash, User.Role role,
                                 String googleSub, String googleEmail,
                                 String encryptedAccessToken, String encryptedRefreshToken,
                                 LocalDateTime tokenExpires) throws SQLException {
        String sql = """
                INSERT INTO users (username, password_hash, role, created_at,
                                   google_sub, google_email,
                                   google_access_token, google_refresh_token,
                                   google_token_expires)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        LocalDateTime now = LocalDateTime.now();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, bcryptHash);
            ps.setString(3, role.name());
            ps.setString(4, now.format(SQLITE_DT));
            ps.setString(5, googleSub);
            ps.setString(6, googleEmail);
            ps.setString(7, encryptedAccessToken);
            ps.setString(8, encryptedRefreshToken);
            ps.setString(9, tokenExpires == null ? null : tokenExpires.format(SQLITE_DT));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long generatedId = keys.next() ? keys.getLong(1) : 0L;
                User user = new User(generatedId, username, bcryptHash, role, now);
                user.setGoogleSub(googleSub);
                user.setGoogleEmail(googleEmail);
                user.setGoogleAccessToken(encryptedAccessToken);
                user.setGoogleRefreshToken(encryptedRefreshToken);
                user.setGoogleTokenExpires(tokenExpires);
                return user;
            }
        }
    }

    /**
     * Replace a user's stored password hash. Used when migrating a
     * legacy BCrypt hash to the new PBKDF2 format on successful
     * login &mdash; the caller already verified the password.
     */
    public void updatePasswordHash(long userId, String newHash) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET password_hash = ? WHERE id = ?")) {
            ps.setString(1, newHash);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public Optional<User> findByUsername(String username) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ALL + " WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(hydrate(rs));
            }
        }
    }

    public Optional<User> findById(long id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ALL + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(hydrate(rs));
            }
        }
    }

    /** Look up a user by their Google "subject" ID (the stable Google user identifier). */
    public Optional<User> findByGoogleSub(String googleSub) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ALL + " WHERE google_sub = ?")) {
            ps.setString(1, googleSub);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(hydrate(rs));
            }
        }
    }

    /**
     * Link an existing user to a Google account. Persists the Google
     * subject ID, email, and initial token set (all pre-encrypted by
     * the caller). Called on the first successful OAuth roundtrip.
     */
    public void linkGoogleAccount(long userId, String googleSub, String googleEmail,
                                  String encryptedAccessToken, String encryptedRefreshToken,
                                  LocalDateTime expiresAt) throws SQLException {
        String sql = """
                UPDATE users
                SET google_sub = ?, google_email = ?,
                    google_access_token = ?, google_refresh_token = ?,
                    google_token_expires = ?
                WHERE id = ?""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, googleSub);
            ps.setString(2, googleEmail);
            ps.setString(3, encryptedAccessToken);
            ps.setString(4, encryptedRefreshToken);
            ps.setString(5, expiresAt.format(SQLITE_DT));
            ps.setLong  (6, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Update just the access token + expiry after a refresh-token exchange.
     * The refresh token itself typically doesn't change across refreshes.
     */
    public void updateGoogleAccessToken(long userId, String encryptedAccessToken,
                                        LocalDateTime expiresAt) throws SQLException {
        String sql = """
                UPDATE users
                SET google_access_token = ?, google_token_expires = ?
                WHERE id = ?""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, encryptedAccessToken);
            ps.setString(2, expiresAt.format(SQLITE_DT));
            ps.setLong  (3, userId);
            ps.executeUpdate();
        }
    }

    /** Unlink Google from a user without deleting the user. */
    public void unlinkGoogleAccount(long userId) throws SQLException {
        String sql = """
                UPDATE users
                SET google_sub = NULL, google_email = NULL,
                    google_access_token = NULL, google_refresh_token = NULL,
                    google_token_expires = NULL
                WHERE id = ?""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    private static final String SELECT_ALL = """
            SELECT id, username, password_hash, role, created_at,
                   google_sub, google_email,
                   google_access_token, google_refresh_token, google_token_expires
            FROM users""";

    private User hydrate(ResultSet rs) throws SQLException {
        long          id           = rs.getLong("id");
        String        username     = rs.getString("username");
        String        passwordHash = rs.getString("password_hash");
        User.Role     role         = User.Role.valueOf(rs.getString("role"));
        String        createdRaw   = rs.getString("created_at");
        LocalDateTime createdAt    = LocalDateTime.parse(
                createdRaw.length() > 19 ? createdRaw.substring(0, 19) : createdRaw,
                SQLITE_DT);
        User user = new User(id, username, passwordHash, role, createdAt);
        user.setGoogleSub         (rs.getString("google_sub"));
        user.setGoogleEmail       (rs.getString("google_email"));
        user.setGoogleAccessToken (rs.getString("google_access_token"));
        user.setGoogleRefreshToken(rs.getString("google_refresh_token"));
        String expiresRaw = rs.getString("google_token_expires");
        if (expiresRaw != null && !expiresRaw.isBlank()) {
            user.setGoogleTokenExpires(LocalDateTime.parse(
                    expiresRaw.length() > 19 ? expiresRaw.substring(0, 19) : expiresRaw,
                    SQLITE_DT));
        }
        return user;
    }
}
