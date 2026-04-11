package com.atlas.roots.dao;

import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.IdeaNode;
import com.atlas.roots.model.NodeType;

import java.sql.*;

/**
 * DAO for {@link IdeaNode} — owns the {@code idea_attrs} table.
 *
 * <p>The Obsidian watcher writes through this DAO when it ingests
 * markdown files, using {@link #upsertByVaultPath} to keep the vault
 * file as the source of truth: re-ingesting the same path updates
 * the existing node rather than creating a duplicate.</p>
 */
public class IdeaNodeDao extends NodeRepository<IdeaNode> {

    public IdeaNodeDao(DatabaseManager db) {
        super(db, NodeType.IDEA);
    }

    @Override
    protected void insertAttrs(Connection conn, IdeaNode node) throws SQLException {
        String sql = """
                INSERT INTO idea_attrs (node_id, vault_path, word_count, backlink_count,
                                        last_edited_at, tags)
                VALUES (?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong  (1, node.getId());
            ps.setString(2, node.getVaultPath());
            ps.setInt   (3, node.getWordCount());
            ps.setInt   (4, node.getBacklinkCount());
            ps.setString(5, fmt(node.getLastEditedAt()));
            ps.setString(6, node.getTags());
            ps.executeUpdate();
        }
    }

    @Override
    protected void updateAttrs(Connection conn, IdeaNode node) throws SQLException {
        String sql = """
                UPDATE idea_attrs
                SET vault_path = ?, word_count = ?, backlink_count = ?,
                    last_edited_at = ?, tags = ?
                WHERE node_id = ?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, node.getVaultPath());
            ps.setInt   (2, node.getWordCount());
            ps.setInt   (3, node.getBacklinkCount());
            ps.setString(4, fmt(node.getLastEditedAt()));
            ps.setString(5, node.getTags());
            ps.setLong  (6, node.getId());
            ps.executeUpdate();
        }
    }

    @Override
    protected IdeaNode hydrate(Connection conn, ResultSet nodeRow) throws SQLException {
        long   id          = nodeRow.getLong("id");
        String name        = nodeRow.getString("name");
        String description = nodeRow.getString("description");
        long   ownerId     = nodeRow.getLong("owner_id");
        var    createdAt   = parse(nodeRow.getString("created_at"));
        var    lastTouched = parse(nodeRow.getString("last_touched"));
        boolean archived   = nodeRow.getInt("archived") == 1;

        String sql = "SELECT * FROM idea_attrs WHERE node_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Missing idea_attrs row for node " + id);
                }
                String vaultPath     = rs.getString("vault_path");
                int    wordCount     = rs.getInt("word_count");
                int    backlinkCount = rs.getInt("backlink_count");
                var    lastEditedAt  = parse(rs.getString("last_edited_at"));
                String tags          = rs.getString("tags");

                return new IdeaNode(id, name, description, ownerId,
                                    createdAt, lastTouched, archived,
                                    vaultPath, wordCount, backlinkCount,
                                    lastEditedAt, tags);
            }
        }
    }

    /**
     * Look up an existing IdeaNode by its vault path. Used by the
     * Obsidian watcher to decide insert vs update on file events.
     */
    public java.util.Optional<IdeaNode> findByVaultPath(long ownerId, String vaultPath) throws SQLException {
        String sql = """
                SELECT n.id, n.name, n.description, n.owner_id, n.created_at,
                       n.last_touched, n.archived
                FROM nodes n
                JOIN idea_attrs i ON i.node_id = n.id
                WHERE n.type = 'IDEA' AND n.owner_id = ? AND i.vault_path = ?""";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong  (1, ownerId);
            ps.setString(2, vaultPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(hydrate(conn, rs));
            }
        }
    }

    /**
     * Insert if absent, update in place if a node already exists for
     * this vault path. Used by the Obsidian watcher's ingest loop.
     */
    public IdeaNode upsertByVaultPath(IdeaNode candidate) throws SQLException {
        var existing = findByVaultPath(candidate.getOwnerId(), candidate.getVaultPath());
        if (existing.isPresent()) {
            IdeaNode found = existing.get();
            found.setName(candidate.getName());
            found.setDescription(candidate.getDescription());
            found.setWordCount(candidate.getWordCount());
            found.setBacklinkCount(candidate.getBacklinkCount());
            found.setLastEditedAt(candidate.getLastEditedAt());
            found.setTags(candidate.getTags());
            found.touch();
            update(found);
            return found;
        }
        return insert(candidate);
    }
}
