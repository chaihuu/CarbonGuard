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
import java.util.Optional;
import xyz.chaihu.carbonguard.data.model.BindTicketRecord;
import xyz.chaihu.carbonguard.data.model.EmailChallenge;

/** Persistence for short-lived bind tickets and e-mail challenges. */
public final class VerificationRepository {
    public void insertTicket(Connection connection, BindTicketRecord ticket) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO bind_tickets(token_hash, identity_key, player_name, ip,
                                         created_at, expires_at, used_at)
                VALUES(?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, ticket.tokenHash());
            ps.setString(2, ticket.identityKey());
            ps.setString(3, ticket.playerName());
            ps.setString(4, ticket.ip());
            ps.setLong(5, ticket.createdAt());
            ps.setLong(6, ticket.expiresAt());
            ps.setLong(7, ticket.usedAt());
            ps.executeUpdate();
        }
    }

    public Optional<BindTicketRecord> findTicket(Connection connection, String tokenHash, long now)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT token_hash, identity_key, player_name, ip, created_at, expires_at, used_at
                FROM bind_tickets WHERE token_hash=? AND used_at=0 AND expires_at>?
                """)) {
            ps.setString(1, tokenHash);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BindTicketRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)));
            }
        }
    }

    public boolean consumeTicket(Connection connection, String tokenHash, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE bind_tickets SET used_at=? WHERE token_hash=? AND used_at=0 AND expires_at>?
                """)) {
            ps.setLong(1, now);
            ps.setString(2, tokenHash);
            ps.setLong(3, now);
            return ps.executeUpdate() == 1;
        }
    }

    public void insertChallenge(Connection connection, EmailChallenge challenge) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO email_challenges(challenge_hash, ticket_hash, identity_key, player_name, email,
                                            code_salt, code_hash, attempts, created_at, expires_at, consumed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, challenge.challengeHash());
            ps.setString(2, challenge.ticketHash());
            ps.setString(3, challenge.identityKey());
            ps.setString(4, challenge.playerName());
            ps.setString(5, challenge.email());
            ps.setString(6, challenge.codeSalt());
            ps.setString(7, challenge.codeHash());
            ps.setInt(8, challenge.attempts());
            ps.setLong(9, challenge.createdAt());
            ps.setLong(10, challenge.expiresAt());
            ps.setLong(11, challenge.consumedAt());
            ps.executeUpdate();
        }
    }

    public Optional<EmailChallenge> findChallenge(Connection connection, String challengeHash)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT challenge_hash, ticket_hash, identity_key, player_name, email, code_salt, code_hash,
                       attempts, created_at, expires_at, consumed_at
                FROM email_challenges WHERE challenge_hash=?
                """)) {
            ps.setString(1, challengeHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public int incrementAttempts(Connection connection, String challengeHash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE email_challenges SET attempts=attempts+1 WHERE challenge_hash=?
                """)) {
            ps.setString(1, challengeHash);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT attempts FROM email_challenges WHERE challenge_hash=?")) {
            ps.setString(1, challengeHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean consumeChallenge(Connection connection, String challengeHash, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE email_challenges SET consumed_at=?
                WHERE challenge_hash=? AND consumed_at=0 AND expires_at>?
                """)) {
            ps.setLong(1, now);
            ps.setString(2, challengeHash);
            ps.setLong(3, now);
            return ps.executeUpdate() == 1;
        }
    }

    public int deleteChallenge(Connection connection, String challengeHash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM email_challenges WHERE challenge_hash=?")) {
            ps.setString(1, challengeHash);
            return ps.executeUpdate();
        }
    }

    /** Invalidates pending tickets/challenges after an admin unbind. */
    public int deleteForIdentity(Connection connection, String identityKey) throws SQLException {
        int removed = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM bind_tickets WHERE identity_key=?")) {
            ps.setString(1, identityKey);
            removed += ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM email_challenges WHERE identity_key=?")) {
            ps.setString(1, identityKey);
            removed += ps.executeUpdate();
        }
        return removed;
    }

    public int countRecentChallenges(Connection connection, String identityKey, long since) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM email_challenges WHERE identity_key=? AND created_at>?")) {
            ps.setString(1, identityKey);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static EmailChallenge map(ResultSet rs) throws SQLException {
        return new EmailChallenge(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getLong(9),
                rs.getLong(10), rs.getLong(11));
    }
}
