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
import xyz.chaihu.carbonguard.data.model.PlayerProfileView;
import xyz.chaihu.carbonguard.util.IpUtil;

/** Player history persisted in {@code player_profiles}. */
public final class PlayerRepository {
    public void ensureExists(Connection connection, String identityKey, String playerName, long now)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO player_profiles(identity_key, player_name, first_join, last_login,
                                            login_count, play_seconds, last_online_at)
                VALUES(?,?,?,?,0,0,?)
                ON CONFLICT(identity_key) DO UPDATE SET player_name=excluded.player_name
                """)) {
            ps.setString(1, identityKey);
            ps.setString(2, playerName);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    /** Creates the profile on first join and otherwise leaves first_join untouched. */
    public void join(Connection connection, String identityKey, String playerName, long now)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO player_profiles(identity_key, player_name, first_join, last_login,
                                            login_count, play_seconds, last_online_at)
                VALUES(?,?,?,?,1,0,?)
                ON CONFLICT(identity_key) DO UPDATE SET
                    player_name=excluded.player_name,
                    last_login=excluded.last_login,
                    last_online_at=excluded.last_online_at,
                    login_count=player_profiles.login_count+1
                """)) {
            ps.setString(1, identityKey);
            ps.setString(2, playerName);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    public void addPlaytime(Connection connection, String identityKey, long seconds) throws SQLException {
        if (seconds <= 0) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE player_profiles SET play_seconds=play_seconds+? WHERE identity_key=?")) {
            ps.setLong(1, seconds);
            ps.setString(2, identityKey);
            ps.executeUpdate();
        }
    }

    /** Looks up a profile by identity key, falling back to an IP history match. */
    public Optional<PlayerProfileView> find(Connection connection, String query) throws SQLException {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        Optional<PlayerProfileView> direct = findExact(connection, query.trim());
        if (direct.isPresent() || !IpUtil.isLiteral(query.trim())) {
            return direct;
        }
        String ip = IpUtil.normalize(query.trim());
        try (PreparedStatement ps = connection.prepareStatement(selectBase() + """
                 JOIN ip_history h ON h.identity_key=p.identity_key
                 WHERE h.ip=?
                 ORDER BY p.last_login DESC LIMIT 1
                """)) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<PlayerProfileView> findExact(Connection connection, String identityKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(selectBase() + " WHERE p.identity_key=?")) {
            ps.setString(1, identityKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<PlayerProfileView> findByPlayerName(Connection connection, String playerName)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(selectBase()
                + " WHERE p.player_name=? COLLATE NOCASE ORDER BY p.last_login DESC LIMIT 1")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<PlayerProfileView> search(Connection connection, String query, int limit) throws SQLException {
        List<PlayerProfileView> result = new ArrayList<>();
        String like = "%" + query + "%";
        try (PreparedStatement ps = connection.prepareStatement(selectBase() + """
                 WHERE ?=''
                    OR p.player_name LIKE ? COLLATE NOCASE
                    OR p.identity_key LIKE ?
                    OR COALESCE(b.email,'') LIKE ? COLLATE NOCASE
                    OR COALESCE(b.qq,'') LIKE ?
                 ORDER BY p.last_login DESC LIMIT ?
                """)) {
            ps.setString(1, query);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setString(5, like);
            ps.setInt(6, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
        }
        return result;
    }

    /** One-shot backfill: older identities that joined before profiles existed. */
    public int backfillFromIdentities(Connection connection, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO player_profiles(identity_key, player_name, first_join, last_login,
                                            login_count, play_seconds, last_online_at)
                SELECT i.identity_key, i.player_name, i.first_seen, i.last_seen, 0, 0, i.last_seen
                FROM identities i
                WHERE NOT EXISTS (SELECT 1 FROM player_profiles p WHERE p.identity_key=i.identity_key)
                """)) {
            return ps.executeUpdate();
        }
    }

    public int deleteStale(Connection connection, long cutoff, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                DELETE FROM player_profiles WHERE identity_key IN (
                    SELECT p.identity_key FROM player_profiles p
                    LEFT JOIN bindings b ON b.identity_key=p.identity_key
                    LEFT JOIN bans n ON n.identity_key=p.identity_key
                    WHERE p.last_login < ? AND b.identity_key IS NULL AND n.identity_key IS NULL
                    LIMIT ?
                )
                """)) {
            ps.setLong(1, cutoff);
            ps.setInt(2, limit);
            return ps.executeUpdate();
        }
    }

    public int deleteByIdentity(Connection connection, String identityKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM player_profiles WHERE identity_key=?")) {
            ps.setString(1, identityKey);
            return ps.executeUpdate();
        }
    }

    private static String selectBase() {
        return """
                SELECT p.identity_key, p.player_name, i.identity_source, p.first_join,
                       COALESCE(b.verified_at,0) AS bound_at, b.email,
                       p.last_login, p.login_count, p.play_seconds,
                       i.last_seen, i.last_ip
                FROM player_profiles p
                LEFT JOIN identities i ON i.identity_key=p.identity_key
                LEFT JOIN bindings b ON b.identity_key=p.identity_key
                """;
    }

    private static PlayerProfileView map(ResultSet rs) throws SQLException {
        return new PlayerProfileView(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getString(6), rs.getLong(7), rs.getInt(8), rs.getLong(9), rs.getLong(10),
                rs.getString(11));
    }
}
