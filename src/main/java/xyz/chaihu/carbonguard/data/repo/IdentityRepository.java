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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import xyz.chaihu.carbonguard.data.model.IdentitySource;
import xyz.chaihu.carbonguard.data.model.IdentityType;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.util.TimeUtil;

public final class IdentityRepository {
    public void upsert(Connection connection, PlayerIdentity identity, String ip, long now) throws SQLException {
        String sql = """
                INSERT INTO identities(identity_key, identity_type, xuid, observed_xuid, uuid, player_name,
                                       identity_source, first_seen, last_seen, last_ip)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(identity_key) DO UPDATE SET
                    identity_type=CASE
                        WHEN identities.identity_type='XUID' OR excluded.identity_type='XUID' THEN 'XUID'
                        ELSE 'UUID' END,
                    xuid=COALESCE(excluded.xuid, identities.xuid),
                    observed_xuid=COALESCE(excluded.observed_xuid, identities.observed_xuid),
                    uuid=excluded.uuid,
                    player_name=excluded.player_name,
                    identity_source=CASE
                        WHEN identities.identity_source='FLOODGATE' OR excluded.identity_source='FLOODGATE'
                            THEN 'FLOODGATE'
                        WHEN identities.identity_source='UUID_FALLBACK' OR excluded.identity_source='UUID_FALLBACK'
                            THEN 'UUID_FALLBACK'
                        ELSE 'JAVA' END,
                    last_seen=excluded.last_seen,
                    last_ip=excluded.last_ip
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, identity.key());
            ps.setString(2, identity.type().name());
            ps.setString(3, identity.xuid());
            ps.setString(4, identity.observedXuid());
            ps.setString(5, identity.uuid().toString());
            ps.setString(6, identity.playerName());
            ps.setString(7, identity.source().name());
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.setString(10, ip);
            ps.executeUpdate();
        }
    }

    public void touch(Connection connection, PlayerIdentity identity, String ip, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE identities SET player_name=?, last_seen=?, last_ip=?,
                    observed_xuid=COALESCE(?, observed_xuid),
                    identity_source=CASE
                        WHEN identity_source='FLOODGATE' OR ?='FLOODGATE' THEN 'FLOODGATE'
                        WHEN identity_source='UUID_FALLBACK' OR ?='UUID_FALLBACK' THEN 'UUID_FALLBACK'
                        ELSE 'JAVA' END,
                    identity_type=CASE
                        WHEN identity_type='XUID' OR ?='XUID' THEN 'XUID'
                        ELSE 'UUID' END
                WHERE identity_key=?
                """)) {
            ps.setString(1, identity.playerName());
            ps.setLong(2, now);
            ps.setString(3, ip);
            ps.setString(4, identity.observedXuid());
            ps.setString(5, identity.source().name());
            ps.setString(6, identity.source().name());
            ps.setString(7, identity.type().name());
            ps.setString(8, identity.key());
            ps.executeUpdate();
        }
    }

    public Optional<PlayerIdentity> findByKey(Connection connection, String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(selectBase() + " WHERE identity_key=?")) {
            ps.setString(1, key);
            return one(ps);
        }
    }

    public Optional<PlayerIdentity> findByUuid(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                selectBase() + " WHERE uuid=? ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, uuid.toString());
            return one(ps);
        }
    }

    public Optional<PlayerIdentity> findByPlayerName(Connection connection, String playerName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                selectBase() + " WHERE player_name=? COLLATE NOCASE ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, playerName);
            return one(ps);
        }
    }

    public List<PlayerIdentity> findAll(Connection connection) throws SQLException {
        List<PlayerIdentity> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(selectBase());
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
        }
        return result;
    }

    public List<PlayerIdentity> search(Connection connection, String query, int limit) throws SQLException {
        List<PlayerIdentity> result = new ArrayList<>();
        String like = "%" + query + "%";
        try (PreparedStatement ps = connection.prepareStatement(selectBase()
                + " WHERE player_name LIKE ? COLLATE NOCASE OR uuid LIKE ? OR identity_key LIKE ?"
                + " ORDER BY last_seen DESC LIMIT ?")) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
        }
        return result;
    }

    public List<PlayerIdentity> fallbackWithObservedXuid(Connection connection) throws SQLException {
        List<PlayerIdentity> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(selectBase()
                + " WHERE identity_key LIKE 'uuid:%' AND xuid IS NULL"
                + " AND observed_xuid IS NOT NULL AND observed_xuid<>''");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
        }
        return result;
    }

    public void addAlias(Connection connection, String oldKey, String newKey, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO identity_aliases(old_key, new_key, created_at) VALUES(?,?,?)
                ON CONFLICT(old_key) DO UPDATE SET new_key=excluded.new_key, created_at=excluded.created_at
                """)) {
            ps.setString(1, oldKey);
            ps.setString(2, newKey);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    private static String selectBase() {
        return """
                SELECT identity_key, identity_type, xuid, observed_xuid, uuid, player_name,
                       identity_source, first_seen, last_seen, last_ip
                FROM identities
                """;
    }

    private static Optional<PlayerIdentity> one(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }
    }

    private static PlayerIdentity map(ResultSet rs) throws SQLException {
        return new PlayerIdentity(
                rs.getString("identity_key"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("player_name"),
                rs.getString("xuid"),
                rs.getString("observed_xuid"),
                IdentityType.valueOf(rs.getString("identity_type")),
                IdentitySource.valueOf(rs.getString("identity_source")),
                !"JAVA".equals(rs.getString("identity_source"))
        );
    }

    public void recordLastSeen(Connection connection, String key, String ip) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE identities SET last_seen=?, last_ip=? WHERE identity_key=?")) {
            ps.setLong(1, TimeUtil.now());
            ps.setString(2, ip);
            ps.setString(3, key);
            ps.executeUpdate();
        }
    }
}
