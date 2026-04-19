package com.atlas.roots.dao;

import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.Skill;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DAO for the {@code skills} table.
 *
 * <p>Unlike the node DAOs, this one is built for <b>atomic rebuild</b>
 * rather than incremental edit. The {@link #replaceAllForOwner} method
 * wipes every skill a user has and inserts the new set in a single
 * transaction. This matches how skills are actually produced:
 * {@code SkillExtractor} runs TF-IDF over the whole vault and returns
 * a fresh list; there is no concept of "update this one skill."</p>
 */
public class SkillDao {

    private static final DateTimeFormatter SQLITE_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatabaseManager db;

    public SkillDao(DatabaseManager db) {
        this.db = db;
    }

    /**
     * Atomically replace the entire skill set for one user. Old rows
     * are deleted, new rows are inserted, both on the same connection
     * with a single transaction. If the insert fails halfway, the
     * delete is rolled back and the user's old skills survive.
     */
    public void replaceAllForOwner(long ownerId, List<Skill> newSkills) throws SQLException {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM skills WHERE owner_id = ?")) {
                    del.setLong(1, ownerId);
                    del.executeUpdate();
                }
                if (!newSkills.isEmpty()) {
                    String insertSql = """
                            INSERT INTO skills (owner_id, name, tokens, idea_count, confidence, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)""";
                    try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                        String now = LocalDateTime.now().format(SQLITE_DT);
                        for (Skill s : newSkills) {
                            ins.setLong  (1, ownerId);
                            ins.setString(2, s.getName());
                            ins.setString(3, String.join(",", s.getTokens()));
                            ins.setInt   (4, s.getIdeaCount());
                            ins.setDouble(5, s.getConfidence());
                            ins.setString(6, now);
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Return every skill belonging to the given user, highest-confidence first. */
    public List<Skill> findByOwner(long ownerId) throws SQLException {
        String sql = """
                SELECT id, owner_id, name, tokens, idea_count, confidence, created_at
                FROM skills
                WHERE owner_id = ?
                ORDER BY confidence DESC, idea_count DESC""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ownerId);
            return collect(ps);
        }
    }

    /**
     * Persist a user's rename of a cluster. The signature is derived
     * from the cluster's top-3 tokens (see {@link #signatureOf}).
     * Upserts on {@code (owner_id, signature)}.
     */
    public void upsertOverride(long ownerId, String signature, String displayName) throws SQLException {
        String sql = """
                INSERT INTO skill_overrides (owner_id, signature, display_name)
                VALUES (?, ?, ?)
                ON CONFLICT(owner_id, signature)
                DO UPDATE SET display_name = excluded.display_name""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong  (1, ownerId);
            ps.setString(2, signature);
            ps.setString(3, displayName);
            ps.executeUpdate();
        }
    }

    /**
     * Load all of this user's rename overrides as a lookup map:
     * {@code signature → displayName}. Used by SkillExtractor when
     * reassembling freshly-extracted clusters so renames survive.
     */
    public java.util.Map<String, String> loadOverrides(long ownerId) throws SQLException {
        String sql = "SELECT signature, display_name FROM skill_overrides WHERE owner_id = ?";
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("signature"), rs.getString("display_name"));
                }
            }
        }
        return out;
    }

    /**
     * Deterministic signature for a cluster: sorted lowercase top-3
     * tokens joined by '|'. This survives token reordering between
     * extraction runs and matches clusters that are "the same theme"
     * even if the weights shift slightly.
     */
    public static String signatureOf(java.util.List<String> tokens) {
        int take = Math.min(3, tokens.size());
        java.util.List<String> top = new java.util.ArrayList<>(tokens.subList(0, take));
        top.replaceAll(s -> s == null ? "" : s.toLowerCase().trim());
        java.util.Collections.sort(top);
        return String.join("|", top);
    }

    // -----------------------------------------------------------------
    //  Skill ↔ Subscription linkage (Phase 3)
    // -----------------------------------------------------------------

    /**
     * Link a subscription to a skill. Idempotent — linking the same
     * pair twice is a no-op thanks to the UNIQUE constraint.
     */
    public void linkSubToSkill(long skillId, long subId) throws SQLException {
        String sql = "INSERT OR IGNORE INTO skill_subs (skill_id, sub_id) VALUES (?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, skillId);
            ps.setLong(2, subId);
            ps.executeUpdate();
        }
    }

    /** Unlink a subscription from a skill. Safe on absent pairs. */
    public void unlinkSubFromSkill(long skillId, long subId) throws SQLException {
        String sql = "DELETE FROM skill_subs WHERE skill_id = ? AND sub_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, skillId);
            ps.setLong(2, subId);
            ps.executeUpdate();
        }
    }

    /**
     * Return all sub IDs currently linked to each skill this owner has.
     * Used by the context export + the subs page's "which skills is this
     * tagged to" display.
     */
    public java.util.Map<Long, java.util.List<Long>> subIdsBySkill(long ownerId) throws SQLException {
        String sql = """
                SELECT ss.skill_id, ss.sub_id
                FROM skill_subs ss
                JOIN skills s ON ss.skill_id = s.id
                WHERE s.owner_id = ?""";
        java.util.Map<Long, java.util.List<Long>> out = new java.util.HashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long skillId = rs.getLong("skill_id");
                    long subId   = rs.getLong("sub_id");
                    out.computeIfAbsent(skillId, k -> new java.util.ArrayList<>()).add(subId);
                }
            }
        }
        return out;
    }

    /** Inverse: for each sub, which skills is it tagged with. */
    public java.util.Map<Long, java.util.List<Long>> skillIdsBySub(long ownerId) throws SQLException {
        String sql = """
                SELECT ss.sub_id, ss.skill_id
                FROM skill_subs ss
                JOIN skills s ON ss.skill_id = s.id
                WHERE s.owner_id = ?""";
        java.util.Map<Long, java.util.List<Long>> out = new java.util.HashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long subId   = rs.getLong("sub_id");
                    long skillId = rs.getLong("skill_id");
                    out.computeIfAbsent(subId, k -> new java.util.ArrayList<>()).add(skillId);
                }
            }
        }
        return out;
    }

    private List<Skill> collect(PreparedStatement ps) throws SQLException {
        List<Skill> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Skill s = new Skill();
                s.setId(rs.getLong("id"));
                s.setOwnerId(rs.getLong("owner_id"));
                s.setName(rs.getString("name"));
                String rawTokens = rs.getString("tokens");
                s.setTokens(rawTokens == null || rawTokens.isBlank()
                        ? List.of()
                        : Arrays.asList(rawTokens.split(",")));
                s.setIdeaCount(rs.getInt("idea_count"));
                s.setConfidence(rs.getDouble("confidence"));
                String createdRaw = rs.getString("created_at");
                if (createdRaw != null) {
                    s.setCreatedAt(LocalDateTime.parse(
                            createdRaw.length() > 19 ? createdRaw.substring(0, 19) : createdRaw,
                            SQLITE_DT));
                }
                out.add(s);
            }
        }
        return out;
    }
}
