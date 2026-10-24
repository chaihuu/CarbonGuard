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

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The stable identity used by every rule and database row.
 *
 * <p>XUID is the business key for Bedrock players when Floodgate is present.
 * Without Floodgate the Bukkit UUID is retained as a clearly marked fallback;
 * it is never converted into an XUID by guessing the Floodgate UUID layout.</p>
 */
public record PlayerIdentity(
        String key,
        UUID uuid,
        String playerName,
        String xuid,
        String observedXuid,
        IdentityType type,
        IdentitySource source,
        boolean bedrock
) {
    public PlayerIdentity {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
    }

    public static PlayerIdentity xuid(UUID uuid, String playerName, String xuid, String observedXuid) {
        return new PlayerIdentity("xuid:" + xuid, uuid, playerName, xuid, observedXuid,
                IdentityType.XUID, IdentitySource.FLOODGATE, true);
    }

    public static PlayerIdentity fallback(UUID uuid, String playerName, String observedXuid) {
        return new PlayerIdentity("uuid:" + uuid, uuid, playerName, null, observedXuid,
                IdentityType.UUID, IdentitySource.UUID_FALLBACK, true);
    }

    public static PlayerIdentity javaPlayer(UUID uuid, String playerName) {
        return new PlayerIdentity("uuid:" + uuid, uuid, playerName, null, null,
                IdentityType.UUID, IdentitySource.JAVA, false);
    }

    public boolean isFallback() {
        return source == IdentitySource.UUID_FALLBACK;
    }

    public boolean matchesWhitelistToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String candidate = token.trim();
        if (candidate.equalsIgnoreCase(key)
                || candidate.equalsIgnoreCase(uuid.toString())) {
            return true;
        }
        if (xuid != null && candidate.equalsIgnoreCase(xuid)) {
            return true;
        }
        if (candidate.toLowerCase(Locale.ROOT).startsWith("xuid:")) {
            return xuid != null && candidate.substring(5).equalsIgnoreCase(xuid);
        }
        if (candidate.toLowerCase(Locale.ROOT).startsWith("uuid:")) {
            return candidate.substring(5).equalsIgnoreCase(uuid.toString());
        }
        return false;
    }

    public String displayIdentity() {
        if (xuid != null) {
            return "XUID " + xuid;
        }
        return (isFallback() ? "UUID(降级) " : "UUID ") + uuid;
    }
}
