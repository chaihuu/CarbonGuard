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
import java.sql.SQLException;
import xyz.chaihu.carbonguard.util.TimeUtil;

/** Maintenance statements for the retired ticket/challenge tables and live codes. */
public final class VerificationRepository {
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
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE verification_codes SET consumed_at=? WHERE identity_key=? AND consumed_at=0")) {
            ps.setLong(1, TimeUtil.now());
            ps.setString(2, identityKey);
            removed += ps.executeUpdate();
        }
        return removed;
    }
}
