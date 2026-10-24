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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.chaihu.carbonguard.data.model.Binding;
import xyz.chaihu.carbonguard.data.model.BindingView;

public final class BindingRepository {
    private static final String COLUMNS = "identity_key, email, qq, email_key, verified_at";

    public Map<String, Binding> loadAll(Connection connection) throws SQLException {
        Map<String, Binding> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT " + COLUMNS + " FROM bindings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString(1), map(rs));
            }
        }
        return result;
    }

    public Optional<Binding> find(Connection connection, String identityKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM bindings WHERE identity_key=?")) {
            ps.setString(1, identityKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Binding whose verified e-mail ID equals {@code emailKey} (already normalised). */
    public Optional<Binding> findByEmailKey(Connection connection, String emailKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM bindings WHERE email_key=? COLLATE NOCASE")) {
            ps.setString(1, emailKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Binding> findByEmail(Connection connection, String email) throws SQLException {
        return findByEmailKey(connection, Binding.emailKeyOf(email));
    }

    public boolean emailTaken(Connection connection, String emailKey, String exceptIdentityKey)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM bindings WHERE email_key=? COLLATE NOCASE AND identity_key<>? LIMIT 1")) {
            ps.setString(1, emailKey);
            ps.setString(2, exceptIdentityKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void save(Connection connection, Binding binding, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO bindings(identity_key, email, qq, email_key, verified_at, created_at)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(identity_key) DO UPDATE SET
                    email=excluded.email, qq=excluded.qq, email_key=excluded.email_key,
                    verified_at=excluded.verified_at
                """)) {
            ps.setString(1, binding.identityKey());
            ps.setString(2, binding.email());
            ps.setString(3, binding.qq());
            ps.setString(4, Binding.emailKeyOf(binding.emailKey()));
            ps.setLong(5, binding.verifiedAt());
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    public boolean delete(Connection connection, String identityKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM bindings WHERE identity_key=?")) {
            ps.setString(1, identityKey);
            return ps.executeUpdate() > 0;
        }
    }

    public long count(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM bindings");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public List<BindingView> search(Connection connection, String query, int limit) throws SQLException {
        List<BindingView> result = new ArrayList<>();
        String like = "%" + query + "%";
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT b.identity_key, i.player_name, i.player_id, b.email, b.qq, b.email_key,
                       b.verified_at, i.last_seen
                FROM bindings b JOIN identities i ON i.identity_key=b.identity_key
                WHERE i.player_name LIKE ? COLLATE NOCASE
                   OR b.identity_key LIKE ? OR b.email LIKE ? COLLATE NOCASE OR b.qq LIKE ?
                   OR b.email_key LIKE ? COLLATE NOCASE OR i.player_id LIKE ? COLLATE NOCASE
                ORDER BY b.verified_at DESC LIMIT ?
                """)) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setString(5, like);
            ps.setString(6, like);
            ps.setInt(7, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new BindingView(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(7), rs.getLong(8)));
                }
            }
        }
        return result;
    }

    private static Binding map(ResultSet rs) throws SQLException {
        String email = rs.getString(2);
        String emailKey = rs.getString(4);
        return new Binding(rs.getString(1), email, rs.getString(3),
                emailKey == null || emailKey.isBlank() ? Binding.emailKeyOf(email) : emailKey,
                rs.getLong(5));
    }
}
