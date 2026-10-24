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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import xyz.chaihu.carbonguard.data.model.AssociationHit;
import xyz.chaihu.carbonguard.data.model.BanRecord;
import xyz.chaihu.carbonguard.data.model.BanView;

public final class BanRepository {
    public List<BanRecord> loadActiveBans(Connection connection, long now) throws SQLException {
        List<BanRecord> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, identity_key, player_name, reason, created_by, created_at, expires_at,
                       active, lifted_at, lifted_by
                FROM bans
                WHERE active=1 AND (expires_at=0 OR expires_at>?)
                ORDER BY created_at DESC
                """)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapBan(rs));
                }
            }
        }
        return result;
    }

    public List<AssociationHit> loadActiveAssociations(Connection connection, long now) throws SQLException {
        List<AssociationHit> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT a.id, a.ban_id, a.ip, a.source_identity, b.reason, b.player_name,
                       a.recorded_at, a.expires_at, b.expires_at
                FROM ban_ip_assoc a
                JOIN bans b ON b.id=a.ban_id
                WHERE b.active=1
                  AND (b.expires_at=0 OR b.expires_at>?)
                  AND (a.expires_at=0 OR a.expires_at>?)
                ORDER BY a.recorded_at DESC
                """)) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new AssociationHit(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)));
                }
            }
        }
        return result;
    }

    public Optional<BanRecord> findById(Connection connection, long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, identity_key, player_name, reason, created_by, created_at, expires_at,
                       active, lifted_at, lifted_by
                FROM bans WHERE id=?
                """)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapBan(rs)) : Optional.empty();
            }
        }
    }

    public long insertBan(Connection connection, String identityKey, String playerName, String reason,
                          String createdBy, long now, long expiresAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO bans(identity_key, player_name, reason, created_by, created_at,
                                 expires_at, active, lifted_at, lifted_by)
                VALUES(?,?,?,?,?,?,1,0,NULL)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, identityKey);
            ps.setString(2, playerName);
            ps.setString(3, reason);
            ps.setString(4, createdBy);
            ps.setLong(5, now);
            ps.setLong(6, expiresAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("未能取得封禁记录 ID");
    }

    public List<String> knownIps(Connection connection, String identityKey) throws SQLException {
        List<String> ips = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ip FROM ip_history WHERE identity_key=? ORDER BY last_seen DESC")) {
            ps.setString(1, identityKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ips.add(rs.getString(1));
                }
            }
        }
        return ips;
    }

    public List<String> associateIps(Connection connection, long banId, String identityKey, String currentIp,
                                     long now, long ttlHours) throws SQLException {
        Set<String> ips = new LinkedHashSet<>();
        if (currentIp != null && !currentIp.isBlank() && !"0.0.0.0".equals(currentIp)) {
            ips.add(currentIp);
        }
        for (String known : knownIps(connection, identityKey)) {
            if (known != null && !known.isBlank() && !"0.0.0.0".equals(known)) {
                ips.add(known);
            }
        }
        long expiry = ttlHours <= 0 ? 0 : now + ttlHours * 3_600_000L;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO ban_ip_assoc(ban_id, ip, source_identity, recorded_at, expires_at)
                SELECT ?,?,?,?,?
                WHERE NOT EXISTS (
                    SELECT 1 FROM ban_ip_assoc WHERE ban_id=? AND ip=?
                )
                """)) {
            for (String ip : ips) {
                ps.setLong(1, banId);
                ps.setString(2, ip);
                ps.setString(3, identityKey);
                ps.setLong(4, now);
                ps.setLong(5, expiry);
                ps.setLong(6, banId);
                ps.setString(7, ip);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return List.copyOf(ips);
    }

    public boolean deactivateBan(Connection connection, long id, String actor, long now) throws SQLException {
        boolean changed;
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE bans SET active=0, lifted_at=?, lifted_by=?
                WHERE id=? AND active=1
                """)) {
            ps.setLong(1, now);
            ps.setString(2, actor);
            ps.setLong(3, id);
            changed = ps.executeUpdate() > 0;
        }
        if (changed) {
            deleteAssociationsForBan(connection, id);
        }
        return changed;
    }

    public int deactivateAllForIdentity(Connection connection, String identityKey, String actor, long now)
            throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM bans WHERE identity_key=? AND active=1")) {
            ps.setString(1, identityKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        int changed;
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE bans SET active=0, lifted_at=?, lifted_by=?
                WHERE identity_key=? AND active=1
                """)) {
            ps.setLong(1, now);
            ps.setString(2, actor);
            ps.setString(3, identityKey);
            changed = ps.executeUpdate();
        }
        for (long id : ids) {
            deleteAssociationsForBan(connection, id);
        }
        return changed;
    }

    public int deleteAssociationsForBan(Connection connection, long banId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM ban_ip_assoc WHERE ban_id=?")) {
            ps.setLong(1, banId);
            return ps.executeUpdate();
        }
    }

    public List<String> ipsForBan(Connection connection, long banId, long now) throws SQLException {
        List<String> ips = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ip FROM ban_ip_assoc WHERE ban_id=? AND (expires_at=0 OR expires_at>?) "
                        + "ORDER BY recorded_at DESC")) {
            ps.setLong(1, banId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ips.add(rs.getString(1));
                }
            }
        }
        return ips;
    }

    public List<BanView> search(Connection connection, String query, int limit, long now) throws SQLException {
        List<BanView> result = new ArrayList<>();
        String like = "%" + query + "%";
        String idQuery = query.startsWith("#") ? query.substring(1) : query;
        String sql = """
                SELECT b.id, b.identity_key, b.player_name, b.reason, b.created_by, b.created_at,
                       b.expires_at, b.active, b.lifted_at, b.lifted_by
                FROM bans b
                WHERE ?=''
                   OR b.player_name LIKE ? COLLATE NOCASE
                   OR b.identity_key LIKE ?
                   OR CAST(b.id AS TEXT)=?
                   OR EXISTS (SELECT 1 FROM ban_ip_assoc a WHERE a.ban_id=b.id AND a.ip LIKE ?)
                ORDER BY (b.active=1 AND (b.expires_at=0 OR b.expires_at>?)) DESC, b.created_at DESC
                LIMIT ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setString(4, idQuery);
            ps.setString(5, like);
            ps.setLong(6, now);
            ps.setInt(7, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BanRecord ban = mapBan(rs);
                    result.add(new BanView(ban.id(), ban.identityKey(), ban.playerName(), ban.reason(),
                            ban.createdBy(), ban.createdAt(), ban.expiresAt(), ban.active(),
                            ban.liftedAt(), ban.liftedBy(), ipsForBan(connection, ban.id(), now)));
                }
            }
        }
        return result;
    }

    public long countActive(Connection connection, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(DISTINCT identity_key) FROM bans WHERE active=1 AND (expires_at=0 OR expires_at>?)")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public List<BanRecord> bansForIdentity(Connection connection, String identityKey) throws SQLException {
        List<BanRecord> bans = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, identity_key, player_name, reason, created_by, created_at, expires_at,
                       active, lifted_at, lifted_by
                FROM bans WHERE identity_key=? ORDER BY created_at DESC
                """)) {
            ps.setString(1, identityKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bans.add(mapBan(rs));
                }
            }
        }
        return bans;
    }

    private static BanRecord mapBan(ResultSet rs) throws SQLException {
        return new BanRecord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getInt(8) == 1,
                rs.getLong(9), rs.getString(10));
    }
}
