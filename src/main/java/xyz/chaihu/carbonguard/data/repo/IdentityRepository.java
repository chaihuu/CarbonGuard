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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import xyz.chaihu.carbonguard.data.model.IdentitySource;
import xyz.chaihu.carbonguard.data.model.IdentityType;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.util.TimeUtil;

public final class IdentityRepository {
    /**
     * Upper bound for a binding lookup. The identity index is by design small
     * (only accounts that ever connected), but the query still stops early.
     */
    private static final int LOOKUP_SCAN_LIMIT = 20_000;

    public void upsert(Connection connection, PlayerIdentity identity, String ip, long now) throws SQLException {
        String sql = """
                INSERT INTO identities(identity_key, identity_type, xuid, observed_xuid, uuid, player_name,
                                       player_id, identity_source, first_seen, last_seen, last_ip)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(identity_key) DO UPDATE SET
                    identity_type=CASE
                        WHEN identities.identity_type='XUID' OR excluded.identity_type='XUID' THEN 'XUID'
                        ELSE 'UUID' END,
                    xuid=COALESCE(excluded.xuid, identities.xuid),
                    observed_xuid=COALESCE(excluded.observed_xuid, identities.observed_xuid),
                    uuid=excluded.uuid,
                    player_name=excluded.player_name,
                    player_id=COALESCE(identities.player_id, excluded.player_id),
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
            ps.setString(7, identity.requestedId());
            ps.setString(8, identity.source().name());
            ps.setLong(9, now);
            ps.setLong(10, now);
            ps.setString(11, ip);
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

    /** The Bedrock XUID (Floodgate or observed) recorded for an identity key, if any. */
    public Optional<String> findXuid(Connection connection, String identityKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT xuid, observed_xuid FROM identities WHERE identity_key=?")) {
            ps.setString(1, identityKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String xuid = rs.getString(1);
                if (xuid == null || xuid.isBlank()) {
                    xuid = rs.getString(2);
                }
                return xuid == null || xuid.isBlank() ? Optional.empty() : Optional.of(xuid);
            }
        }
    }

    /**
     * Resolves the account ID a player typed on the binding page. Inputs that look
     * like an XUID or a UUID resolve through their indexed columns; anything else is
     * matched against the last seen player name.
     *
     * <p>An exact match wins. If several identities share the same name (a name
     * change, or an XUID and its UUID fallback), the most recently seen identity is
     * returned, preferring the canonical Floodgate XUID key.</p>
     */
    public Optional<PlayerIdentity> findByPlayerId(Connection connection, String rawId) throws SQLException {
        if (rawId == null) {
            return Optional.empty();
        }
        String id = rawId.trim();
        if (id.isEmpty() || id.length() > 64) {
            return Optional.empty();
        }
        UUID uuid = PlayerIdentity.parseUuid(id);
        if (uuid != null) {
            Optional<PlayerIdentity> exact = findByUuid(connection, uuid);
            if (exact.isPresent()) {
                return exact;
            }
        }
        if (id.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try (PreparedStatement ps = connection.prepareStatement(selectBase()
                    + " WHERE xuid=? OR observed_xuid=?"
                    + " ORDER BY CASE WHEN identity_key LIKE 'xuid:%' THEN 0 ELSE 1 END, last_seen DESC"
                    + " LIMIT 1")) {
                ps.setString(1, id);
                ps.setString(2, id);
                Optional<PlayerIdentity> byXuid = one(ps);
                if (byXuid.isPresent()) {
                    return byXuid;
                }
            }
        }
        // A bare 32-char hex string may have been stored as a UUID without dashes.
        if (uuid == null) {
            UUID compact = PlayerIdentity.parseUuid(id);
            if (compact != null) {
                return findByUuid(connection, compact);
            }
        }
        Map<String, PlayerIdentity> byName = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(selectBase()
                + " WHERE player_name=? COLLATE NOCASE"
                + " ORDER BY CASE WHEN identity_key LIKE 'xuid:%' THEN 0 ELSE 1 END, last_seen DESC"
                + " LIMIT 8")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerIdentity identity = map(rs);
                    byName.putIfAbsent(identity.key(), identity);
                }
            }
        }
        if (!byName.isEmpty()) {
            return Optional.of(byName.values().iterator().next());
        }
        return Optional.empty();
    }

    /** Stores the player-chosen binding ID on the resolved identity. */
    public void setPlayerId(Connection connection, String identityKey, String playerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE identities SET player_id=? WHERE identity_key=?")) {
            ps.setString(1, playerId);
            ps.setString(2, identityKey);
            ps.executeUpdate();
        }
    }

    public List<PlayerIdentity> findAll(Connection connection) throws SQLException {
        List<PlayerIdentity> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(selectBase() + " LIMIT " + LOOKUP_SCAN_LIMIT);
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
                + " OR player_id LIKE ? COLLATE NOCASE"
                + " ORDER BY last_seen DESC LIMIT ?")) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setInt(5, limit);
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
                       player_id, identity_source, first_seen, last_seen, last_ip
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
                rs.getString("player_id"),
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
