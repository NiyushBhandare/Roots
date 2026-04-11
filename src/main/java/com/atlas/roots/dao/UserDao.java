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

    public Optional<User> findByUsername(String username) throws SQLException {
        String sql = "SELECT id, username, password_hash, role, created_at FROM users WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(hydrate(rs));
            }
        }
    }

    public Optional<User> findById(long id) throws SQLException {
        String sql = "SELECT id, username, password_hash, role, created_at FROM users WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(hydrate(rs));
            }
        }
    }

    private User hydrate(ResultSet rs) throws SQLException {
        long          id           = rs.getLong("id");
        String        username     = rs.getString("username");
        String        passwordHash = rs.getString("password_hash");
        User.Role     role         = User.Role.valueOf(rs.getString("role"));
        String        createdRaw   = rs.getString("created_at");
        LocalDateTime createdAt    = LocalDateTime.parse(
                createdRaw.length() > 19 ? createdRaw.substring(0, 19) : createdRaw,
                SQLITE_DT);
        return new User(id, username, passwordHash, role, createdAt);
    }
}
