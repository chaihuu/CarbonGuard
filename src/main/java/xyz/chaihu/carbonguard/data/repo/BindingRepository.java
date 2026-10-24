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
    public Map<String, Binding> loadAll(Connection connection) throws SQLException {
        Map<String, Binding> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT identity_key, email, qq, verified_at FROM bindings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString(1), new Binding(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4)));
            }
        }
        return result;
    }

    public Optional<Binding> find(Connection connection, String identityKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT identity_key, email, qq, verified_at FROM bindings WHERE identity_key=?")) {
            ps.setString(1, identityKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Binding> findByEmail(Connection connection, String email) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT identity_key, email, qq, verified_at FROM bindings WHERE email=? COLLATE NOCASE")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean emailTaken(Connection connection, String email, String exceptIdentityKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM bindings WHERE email=? COLLATE NOCASE AND identity_key<>? LIMIT 1")) {
            ps.setString(1, email);
            ps.setString(2, exceptIdentityKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void save(Connection connection, Binding binding, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO bindings(identity_key, email, qq, verified_at, created_at)
                VALUES(?,?,?,?,?)
                ON CONFLICT(identity_key) DO UPDATE SET
                    email=excluded.email, qq=excluded.qq, verified_at=excluded.verified_at
                """)) {
            ps.setString(1, binding.identityKey());
            ps.setString(2, binding.email());
            ps.setString(3, binding.qq());
            ps.setLong(4, binding.verifiedAt());
            ps.setLong(5, now);
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
                SELECT b.identity_key, i.player_name, b.email, b.qq, b.verified_at, i.last_seen
                FROM bindings b JOIN identities i ON i.identity_key=b.identity_key
                WHERE i.player_name LIKE ? COLLATE NOCASE
                   OR b.identity_key LIKE ? OR b.email LIKE ? COLLATE NOCASE OR b.qq LIKE ?
                ORDER BY b.verified_at DESC LIMIT ?
                """)) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new BindingView(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5), rs.getLong(6)));
                }
            }
        }
        return result;
    }

    private static Binding map(ResultSet rs) throws SQLException {
        return new Binding(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
    }
}
