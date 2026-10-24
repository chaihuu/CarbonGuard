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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import xyz.chaihu.carbonguard.data.model.StatsSnapshot;

public final class StatsRepository {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    public void recordLogin(Connection connection, String identityKey, String playerName, String ip,
                            String outcome, String detail, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO login_events(identity_key, player_name, ip, occurred_at, outcome, detail)
                VALUES(?,?,?,?,?,?)
                """)) {
            ps.setString(1, identityKey);
            ps.setString(2, playerName);
            ps.setString(3, ip);
            ps.setLong(4, now);
            ps.setString(5, outcome);
            ps.setString(6, detail);
            ps.executeUpdate();
        }
    }

    public StatsSnapshot snapshot(Connection connection, long now, int retentionDays, long onlineSessions)
            throws SQLException {
        long identities = scalar(connection, "SELECT COUNT(*) FROM identities");
        long bindings = scalar(connection, "SELECT COUNT(*) FROM bindings");
        long activeBans = scalar(connection,
                "SELECT COUNT(DISTINCT identity_key) FROM bans WHERE active=1 AND (expires_at=0 OR expires_at>?)", now);
        long pending = scalar(connection, "SELECT COUNT(*) FROM reviews WHERE status='PENDING'");
        long since = now - 86_400_000L;
        long allowed = scalar(connection,
                "SELECT COUNT(*) FROM login_events WHERE occurred_at>? AND outcome IN ('ALLOWED','WARN_ONLY')", since);
        long denied = scalar(connection,
                "SELECT COUNT(*) FROM login_events WHERE occurred_at>? AND outcome='DENIED'", since);
        long uniqueIps = scalar(connection,
                "SELECT COUNT(DISTINCT ip) FROM login_events WHERE occurred_at>?", since);

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        int days = Math.max(1, Math.min(30, retentionDays));
        LocalDate start = today.minusDays(days - 1L);
        long startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli();

        Map<String, long[]> series = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            series.put(start.plusDays(i).format(DAY), new long[]{0, 0});
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT occurred_at, outcome FROM login_events
                WHERE occurred_at>=? ORDER BY occurred_at
                """)) {
            ps.setLong(1, startMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String day = Instant.ofEpochMilli(rs.getLong(1)).atZone(zone).toLocalDate().format(DAY);
                    long[] counts = series.get(day);
                    if (counts == null) {
                        continue;
                    }
                    String outcome = rs.getString(2);
                    if ("DENIED".equals(outcome)) {
                        counts[1]++;
                    } else {
                        counts[0]++;
                    }
                }
            }
        }
        List<StatsSnapshot.DayCount> list = new ArrayList<>();
        series.forEach((day, counts) -> list.add(new StatsSnapshot.DayCount(day, counts[0], counts[1])));

        return new StatsSnapshot(identities, bindings, activeBans, pending, allowed, denied,
                uniqueIps, onlineSessions, List.copyOf(list));
    }

    public int purge(Connection connection, long now, int retentionDays) throws SQLException {
        int removed = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM login_events WHERE occurred_at < ?")) {
            ps.setLong(1, now - retentionDays * 86_400_000L);
            removed += ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM email_challenges WHERE expires_at < ?")) {
            ps.setLong(1, now - 86_400_000L);
            removed += ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM bind_tickets WHERE expires_at < ?")) {
            ps.setLong(1, now - 7L * 86_400_000L);
            removed += ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM review_allowlist WHERE expires_at>0 AND expires_at<=?")) {
            ps.setLong(1, now);
            removed += ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                DELETE FROM ban_ip_assoc
                WHERE expires_at>0 AND expires_at<=?
                  AND NOT EXISTS (SELECT 1 FROM bans b WHERE b.id=ban_ip_assoc.ban_id AND b.active=1)
                """)) {
            ps.setLong(1, now);
            removed += ps.executeUpdate();
        }
        return removed;
    }

    public long countIdentities(Connection connection) throws SQLException {
        return scalar(connection, "SELECT COUNT(*) FROM identities");
    }

    private static long scalar(Connection connection, String sql, long parameter) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, parameter);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}
