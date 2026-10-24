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

package xyz.chaihu.carbonguard.data.model;

public record AssociationHit(
        long associationId,
        long banId,
        String ip,
        String sourceIdentity,
        String reason,
        String bannedPlayer,
        long recordedAt,
        long expiresAt,
        long banExpiresAt
) {
    public boolean activeAt(long now) {
        return (expiresAt <= 0 || expiresAt > now) && (banExpiresAt <= 0 || banExpiresAt > now);
    }
}
