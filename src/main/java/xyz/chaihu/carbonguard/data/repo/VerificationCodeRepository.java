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
import java.util.Optional;
import xyz.chaihu.carbonguard.data.model.VerificationCode;

/**
 * Persistence for the one-time e-mail codes of the player-initiated binding page.
 *
 * <p>The browser only ever holds a random request token; the code itself is stored as
 * PBKDF2-HMAC-SHA256 and can be consumed exactly once.</p>
 */
public final class VerificationCodeRepository {
    private static final String COLUMNS = "id, request_hash, player_id, identity_key, player_name,"
            + " email_key, email, code_salt, code_hash, attempts, created_at, expires_at, consumed_at";

    /** Inserts a code and returns its row id so the verification step can be race-free. */
    public long insert(Connection connection, VerificationCode code) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO verification_codes(request_hash, player_id, identity_key, player_name,
                                               email_key, email, code_salt, code_hash, attempts,
                                               created_at, expires_at, consumed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code.requestHash());
            ps.setString(2, code.playerId());
            ps.setString(3, code.identityKey());
            ps.setString(4, code.playerName());
            ps.setString(5, code.emailKey());
            ps.setString(6, code.email());
            ps.setString(7, code.codeSalt());
            ps.setString(8, code.codeHash());
            ps.setInt(9, code.attempts());
            ps.setLong(10, code.createdAt());
            ps.setLong(11, code.expiresAt());
            ps.setLong(12, code.consumedAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("未能获取验证码记录 id");
    }

    public Optional<VerificationCode> findByRequestHash(Connection connection, String requestHash)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM verification_codes WHERE request_hash=?")) {
            ps.setString(1, requestHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<VerificationCode> findActive(Connection connection, long id, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM verification_codes"
                        + " WHERE id=? AND consumed_at=0 AND expires_at>?")) {
            ps.setLong(1, id);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public void deleteById(Connection connection, long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM verification_codes WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public int incrementAttempts(Connection connection, long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE verification_codes SET attempts=attempts+1 WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT attempts FROM verification_codes WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Single-use consumption: only the first caller whose row is still active wins. */
    public boolean consume(Connection connection, long id, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE verification_codes SET consumed_at=? WHERE id=? AND consumed_at=0 AND expires_at>?")) {
            ps.setLong(1, now);
            ps.setLong(2, id);
            ps.setLong(3, now);
            return ps.executeUpdate() > 0;
        }
    }

    /** Invalidates pending codes after a successful binding or an admin unbind. */
    public int consumeForIdentity(Connection connection, String identityKey, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE verification_codes SET consumed_at=? WHERE identity_key=? AND consumed_at=0")) {
            ps.setLong(1, now);
            ps.setString(2, identityKey);
            return ps.executeUpdate();
        }
    }

    public int purgeExpired(Connection connection, long before) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM verification_codes WHERE expires_at < ?")) {
            ps.setLong(1, before);
            return ps.executeUpdate();
        }
    }

    private static VerificationCode map(ResultSet rs) throws SQLException {
        return new VerificationCode(
                rs.getLong("id"),
                rs.getString("request_hash"),
                rs.getString("player_id"),
                rs.getString("identity_key"),
                rs.getString("player_name"),
                rs.getString("email_key"),
                rs.getString("email"),
                rs.getString("code_salt"),
                rs.getString("code_hash"),
                rs.getInt("attempts"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                rs.getLong("consumed_at")
        );
    }
}
