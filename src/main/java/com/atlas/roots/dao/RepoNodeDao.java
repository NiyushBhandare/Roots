package com.atlas.roots.dao;

import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.NodeType;
import com.atlas.roots.model.RepoNode;

import java.sql.*;

/**
 * DAO for {@link RepoNode} — owns the {@code repo_attrs} table.
 */
public class RepoNodeDao extends NodeRepository<RepoNode> {

    public RepoNodeDao(DatabaseManager db) {
        super(db, NodeType.REPO);
    }

    @Override
    protected void insertAttrs(Connection conn, RepoNode node) throws SQLException {
        String sql = """
                INSERT INTO repo_attrs (node_id, local_path, remote_url, primary_language,
                                        commit_count, last_commit_at, stale_threshold_days)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong  (1, node.getId());
            ps.setString(2, node.getLocalPath());
            ps.setString(3, node.getRemoteUrl());
            ps.setString(4, node.getPrimaryLanguage());
            ps.setInt   (5, node.getCommitCount());
            ps.setString(6, fmt(node.getLastCommitAt()));
            ps.setInt   (7, node.getStaleThresholdDays());
            ps.executeUpdate();
        }
    }

    @Override
    protected void updateAttrs(Connection conn, RepoNode node) throws SQLException {
        String sql = """
                UPDATE repo_attrs
                SET local_path = ?, remote_url = ?, primary_language = ?,
                    commit_count = ?, last_commit_at = ?, stale_threshold_days = ?
                WHERE node_id = ?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, node.getLocalPath());
            ps.setString(2, node.getRemoteUrl());
            ps.setString(3, node.getPrimaryLanguage());
            ps.setInt   (4, node.getCommitCount());
            ps.setString(5, fmt(node.getLastCommitAt()));
            ps.setInt   (6, node.getStaleThresholdDays());
            ps.setLong  (7, node.getId());
            ps.executeUpdate();
        }
    }

    @Override
    protected RepoNode hydrate(Connection conn, ResultSet nodeRow) throws SQLException {
        long   id          = nodeRow.getLong("id");
        String name        = nodeRow.getString("name");
        String description = nodeRow.getString("description");
        long   ownerId     = nodeRow.getLong("owner_id");
        var    createdAt   = parse(nodeRow.getString("created_at"));
        var    lastTouched = parse(nodeRow.getString("last_touched"));
        boolean archived   = nodeRow.getInt("archived") == 1;

        String sql = "SELECT * FROM repo_attrs WHERE node_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Missing repo_attrs row for node " + id);
                }
                String localPath          = rs.getString("local_path");
                String remoteUrl          = rs.getString("remote_url");
                String primaryLanguage    = rs.getString("primary_language");
                int    commitCount        = rs.getInt("commit_count");
                var    lastCommitAt       = parse(rs.getString("last_commit_at"));
                int    staleThresholdDays = rs.getInt("stale_threshold_days");

                return new RepoNode(id, name, description, ownerId,
                                    createdAt, lastTouched, archived,
                                    localPath, remoteUrl, primaryLanguage,
                                    commitCount, lastCommitAt, staleThresholdDays);
            }
        }
    }
}
