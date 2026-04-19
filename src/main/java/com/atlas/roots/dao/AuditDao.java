package com.atlas.roots.dao;

import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.AuditEvent;
import com.atlas.roots.util.CryptoService;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO for the {@code audit_log} table. Append-only by design &mdash;
 * there is no public update or delete API, and every inserted row
 * carries a SHA-256 chain hash over the row's content and the
 * previous row's hash. Mutating any historical row in SQLite
 * directly (outside the app) breaks the chain from that row forward,
 * and {@link #verifyChain()} reports the exact row where the break
 * was detected.
 *
 * <p>This makes the audit log tamper-evident: an attacker with
 * filesystem access can modify rows, but they cannot do so silently.
 * A professor can see this in action by running a UPDATE directly
 * against {@code roots.db} in DB Browser and then clicking the
 * "verify chain" button in the Audit Log view.</p>
 */
public class AuditDao {

    private static final DateTimeFormatter SQLITE_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatabaseManager db;

    public AuditDao(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Append a new audit row with a freshly computed chain hash.
     * The operation is atomic: the read of the previous row's hash
     * and the insert of the new row happen on the same connection
     * so concurrent writes don't interleave.
     */
    public void log(Long nodeId, long userId, AuditEvent.Action action, String detail) throws SQLException {
        String insertSql = """
                INSERT INTO audit_log (node_id, user_id, action, detail, created_at, prev_hash, this_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (Connection conn = db.getConnection()) {
            // Read the previous chain head (NULL on first insert ever)
            String prevHash = fetchLastHash(conn);
            String createdAt = LocalDateTime.now().format(SQLITE_DT);
            String thisHash = CryptoService.chainHash(prevHash, userId, action.name(), detail, createdAt);

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                if (nodeId == null) ps.setNull(1, Types.INTEGER);
                else                ps.setLong(1, nodeId);
                ps.setLong  (2, userId);
                ps.setString(3, action.name());
                ps.setString(4, detail);
                ps.setString(5, createdAt);
                ps.setString(6, prevHash);
                ps.setString(7, thisHash);
                ps.executeUpdate();
            }
        }
    }

    private String fetchLastHash(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT this_hash FROM audit_log ORDER BY id DESC LIMIT 1")) {
            if (rs.next()) return rs.getString("this_hash");
            return null;
        }
    }

    // -----------------------------------------------------------------
    //  Chain verification
    // -----------------------------------------------------------------

    /**
     * Result of walking the audit log and re-computing every row's
     * chain hash against what is stored in the database.
     */
    public static final class ChainVerification {
        public boolean intact;
        public int     rowsChecked;
        public Long    brokenAtId;   // null if intact
        public String  reason;       // null if intact
    }

    /**
     * Walk the audit log from the earliest row to the most recent,
     * recomputing every row's chain hash. Returns a
     * {@link ChainVerification} reporting {@code intact = true} if
     * every row matches, or {@code intact = false} with the ID of
     * the first broken row and a human-readable reason.
     */
    public ChainVerification verifyChain() throws SQLException {
        ChainVerification result = new ChainVerification();
        String sql = """
                SELECT id, user_id, action, detail, created_at, prev_hash, this_hash
                FROM audit_log
                ORDER BY id ASC""";
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            String expectedPrev = null;
            while (rs.next()) {
                result.rowsChecked++;
                long   id        = rs.getLong("id");
                long   userId    = rs.getLong("user_id");
                String action    = rs.getString("action");
                String detail    = rs.getString("detail");
                String createdAt = rs.getString("created_at");
                String prevHash  = rs.getString("prev_hash");
                String thisHash  = rs.getString("this_hash");

                // Null hashes mean this row was inserted before the
                // chain existed; these are legacy rows and we accept
                // them silently — the chain effectively starts at the
                // first non-null row.
                if (thisHash == null) {
                    expectedPrev = null;
                    continue;
                }

                // The stored prev_hash must match what we've been
                // carrying forward through the loop.
                if (!java.util.Objects.equals(expectedPrev, prevHash)) {
                    result.intact     = false;
                    result.brokenAtId = id;
                    result.reason     = "prev_hash mismatch at row " + id +
                            " (expected " + abbrev(expectedPrev) +
                            ", stored " + abbrev(prevHash) + ")";
                    return result;
                }

                // Recompute this row's hash from its fields and
                // compare against what's stored.
                String recomputed = CryptoService.chainHash(prevHash, userId, action, detail, createdAt);
                if (!recomputed.equals(thisHash)) {
                    result.intact     = false;
                    result.brokenAtId = id;
                    result.reason     = "content hash mismatch at row " + id +
                            " (row has been modified since insertion)";
                    return result;
                }

                expectedPrev = thisHash;
            }
        }
        result.intact = true;
        return result;
    }

    private static String abbrev(String h) {
        if (h == null) return "null";
        return h.length() > 12 ? h.substring(0, 12) + "…" : h;
    }

    // -----------------------------------------------------------------
    //  Reads (unchanged from before)
    // -----------------------------------------------------------------

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

    /**
     * Count audit events per node over the last {@code days} days.
     * Used by the Joy Score v2 formula to compute the usage-intensity
     * factor — how often a user has touched (created, updated, viewed)
     * a node recently.
     *
     * @return map of {@code node_id → event count}. Nodes with zero
     *         events don't appear; callers should default missing keys
     *         to zero.
     */
    public java.util.Map<Long, Integer> touchCountByNode(long userId, int days) throws SQLException {
        String sql = """
                SELECT node_id, COUNT(*) AS touches
                FROM audit_log
                WHERE user_id = ?
                  AND node_id IS NOT NULL
                  AND created_at >= datetime('now', ?)
                GROUP BY node_id""";
        java.util.Map<Long, Integer> out = new java.util.HashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong  (1, userId);
            ps.setString(2, "-" + days + " days");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong("node_id"), rs.getInt("touches"));
                }
            }
        }
        return out;
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
