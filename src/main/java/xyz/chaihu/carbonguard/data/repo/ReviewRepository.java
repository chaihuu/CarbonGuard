/*
 * CarbonGuard
 * Copyright (C) 2026 chaihuu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 chaihuu
 * SPDX-License-Identifier: GPL-3.0-only
 */

package xyz.chaihu.carbonguard.data.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import xyz.chaihu.carbonguard.data.model.ReviewRecord;
import xyz.chaihu.carbonguard.data.model.ReviewStatus;
import xyz.chaihu.carbonguard.data.model.ReviewType;

public final class ReviewRepository {
    public boolean insertIfAbsent(Connection connection, ReviewType type, String identityKey,
                                  String playerName, String ip, Long banId, String reason, long now)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO reviews(type, identity_key, player_name, ip, ban_id, reason, status,
                                    created_at, reviewed_at, reviewed_by)
                SELECT ?,?,?,?,?,?, 'PENDING', ?, 0, NULL
                WHERE NOT EXISTS (
                    SELECT 1 FROM reviews WHERE type=? AND identity_key=? AND ip=? AND status='PENDING'
                )
                """)) {
            ps.setString(1, type.name());
            ps.setString(2, identityKey);
            ps.setString(3, playerName);
            ps.setString(4, ip);
            if (banId == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setLong(5, banId);
            }
            ps.setString(6, reason);
            ps.setLong(7, now);
            ps.setString(8, type.name());
            ps.setString(9, identityKey);
            ps.setString(10, ip);
            return ps.executeUpdate() > 0;
        }
    }

    public List<ReviewRecord> list(Connection connection, ReviewStatus status, int limit) throws SQLException {
        List<ReviewRecord> result = new ArrayList<>();
        String sql = baseSelect()
                + (status == null ? "" : " WHERE status=?")
                + " ORDER BY CASE status WHEN 'PENDING' THEN 0 ELSE 1 END, created_at DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            if (status != null) {
                ps.setString(index++, status.name());
            }
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
        }
        return result;
    }

    public Optional<ReviewRecord> find(Connection connection, long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(baseSelect() + " WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean updateStatus(Connection connection, long id, ReviewStatus status, String actor, long now)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE reviews SET status=?, reviewed_at=?, reviewed_by=?
                WHERE id=? AND status='PENDING'
                """)) {
            ps.setString(1, status.name());
            ps.setLong(2, now);
            ps.setString(3, actor);
            ps.setLong(4, id);
            return ps.executeUpdate() > 0;
        }
    }

    public long countPending(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM reviews WHERE status='PENDING'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static String baseSelect() {
        return """
                SELECT id, type, identity_key, player_name, ip, ban_id, reason, status,
                       created_at, reviewed_at, reviewed_by
                FROM reviews
                """;
    }

    private static ReviewRecord map(ResultSet rs) throws SQLException {
        long banId = rs.getLong("ban_id");
        Long boxedBanId = rs.wasNull() ? null : banId;
        return new ReviewRecord(
                rs.getLong("id"),
                ReviewType.valueOf(rs.getString("type")),
                rs.getString("identity_key"),
                rs.getString("player_name"),
                rs.getString("ip"),
                boxedBanId,
                rs.getString("reason"),
                ReviewStatus.valueOf(rs.getString("status")),
                rs.getLong("created_at"),
                rs.getLong("reviewed_at"),
                rs.getString("reviewed_by")
        );
    }
}
