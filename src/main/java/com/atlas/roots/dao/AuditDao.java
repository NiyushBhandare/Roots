package com.atlas.roots.dao;

import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.AuditEvent;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO for the {@code audit_log} table. Append-only by design — there
 * is no public update or delete API; the audit trail must be tamper-
 * resistant from the application layer's perspective.
 */
public class AuditDao {

    private static final DateTimeFormatter SQLITE_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatabaseManager db;

    public AuditDao(DatabaseManager db) {
        this.db = db;
    }

    public void log(Long nodeId, long userId, AuditEvent.Action action, String detail) throws SQLException {
        String sql = """
                INSERT INTO audit_log (node_id, user_id, action, detail, created_at)
                VALUES (?, ?, ?, ?, ?)""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (nodeId == null) ps.setNull(1, Types.INTEGER);
            else                ps.setLong(1, nodeId);
            ps.setLong  (2, userId);
            ps.setString(3, action.name());
            ps.setString(4, detail);
            ps.setString(5, LocalDateTime.now().format(SQLITE_DT));
            ps.executeUpdate();
        }
    }

    public List<AuditEvent> recent(int limit) throws SQLException {
        String sql = """
                SELECT id, node_id, user_id, action, detail, created_at
                FROM audit_log
                ORDER BY id DESC
                LIMIT ?""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            return collect(ps);
        }
    }

    public List<AuditEvent> recentForUser(long userId, int limit) throws SQLException {
        String sql = """
                SELECT id, node_id, user_id, action, detail, created_at
                FROM audit_log
                WHERE user_id = ?
                ORDER BY id DESC
                LIMIT ?""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt (2, limit);
            return collect(ps);
        }
    }

    private List<AuditEvent> collect(PreparedStatement ps) throws SQLException {
        List<AuditEvent> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long  id     = rs.getLong("id");
                long  nodeId = rs.getLong("node_id");
                Long  nodeBoxed = rs.wasNull() ? null : nodeId;
                long  userId = rs.getLong("user_id");
                var   action = AuditEvent.Action.valueOf(rs.getString("action"));
                String detail = rs.getString("detail");
                String createdRaw = rs.getString("created_at");
                LocalDateTime createdAt = LocalDateTime.parse(
                        createdRaw.length() > 19 ? createdRaw.substring(0, 19) : createdRaw,
                        SQLITE_DT);
                out.add(new AuditEvent(id, nodeBoxed, userId, action, detail, createdAt));
            }
        }
        return out;
    }
}
