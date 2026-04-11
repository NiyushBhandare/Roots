package com.atlas.roots.dao;

import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.NodeType;
import com.atlas.roots.model.SubNode;

import java.sql.*;
import java.time.LocalDate;

/**
 * DAO for {@link SubNode} — owns the {@code sub_attrs} table.
 */
public class SubNodeDao extends NodeRepository<SubNode> {

    public SubNodeDao(DatabaseManager db) {
        super(db, NodeType.SUB);
    }

    @Override
    protected void insertAttrs(Connection conn, SubNode node) throws SQLException {
        String sql = """
                INSERT INTO sub_attrs (node_id, monthly_cost, currency, cadence,
                                       joy_rating, started_on, next_renewal)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindAttrs(ps, node);
            ps.executeUpdate();
        }
    }

    @Override
    protected void updateAttrs(Connection conn, SubNode node) throws SQLException {
        String sql = """
                UPDATE sub_attrs
                SET monthly_cost = ?, currency = ?, cadence = ?, joy_rating = ?,
                    started_on = ?, next_renewal = ?
                WHERE node_id = ?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, node.getMonthlyCost());
            ps.setString(2, node.getCurrency());
            ps.setString(3, node.getCadence().name());
            ps.setInt   (4, node.getJoyRating());
            ps.setString(5, node.getStartedOn() == null ? null : node.getStartedOn().toString());
            ps.setString(6, node.getNextRenewal() == null ? null : node.getNextRenewal().toString());
            ps.setLong  (7, node.getId());
            ps.executeUpdate();
        }
    }

    private void bindAttrs(PreparedStatement ps, SubNode node) throws SQLException {
        ps.setLong  (1, node.getId());
        ps.setDouble(2, node.getMonthlyCost());
        ps.setString(3, node.getCurrency());
        ps.setString(4, node.getCadence().name());
        ps.setInt   (5, node.getJoyRating());
        ps.setString(6, node.getStartedOn() == null ? null : node.getStartedOn().toString());
        ps.setString(7, node.getNextRenewal() == null ? null : node.getNextRenewal().toString());
    }

    @Override
    protected SubNode hydrate(Connection conn, ResultSet nodeRow) throws SQLException {
        long   id          = nodeRow.getLong("id");
        String name        = nodeRow.getString("name");
        String description = nodeRow.getString("description");
        long   ownerId     = nodeRow.getLong("owner_id");
        var    createdAt   = parse(nodeRow.getString("created_at"));
        var    lastTouched = parse(nodeRow.getString("last_touched"));
        boolean archived   = nodeRow.getInt("archived") == 1;

        String sql = "SELECT * FROM sub_attrs WHERE node_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Missing sub_attrs row for node " + id);
                }
                double  monthlyCost = rs.getDouble("monthly_cost");
                String  currency    = rs.getString("currency");
                var     cadence     = SubNode.Cadence.valueOf(rs.getString("cadence"));
                int     joyRating   = rs.getInt("joy_rating");
                LocalDate started   = rs.getString("started_on")   == null ? null : LocalDate.parse(rs.getString("started_on"));
                LocalDate renewal   = rs.getString("next_renewal") == null ? null : LocalDate.parse(rs.getString("next_renewal"));

                return new SubNode(id, name, description, ownerId,
                                   createdAt, lastTouched, archived,
                                   monthlyCost, currency, cadence, joyRating,
                                   started, renewal);
            }
        }
    }
}
