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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The stable identity used by every rule and database row.
 *
 * <p>XUID is the business key for Bedrock players when Floodgate is present.
 * Without Floodgate the Bukkit UUID is retained as a clearly marked fallback;
 * it is never converted into an XUID by guessing the Floodgate UUID layout.</p>
 *
 * <p>{@code playerId} is the account ID the player types on the {@code /bind} page.
 * It is matched against the XUID, the Mojang UUID, or the last seen player name by
 * {@link #matchId(String)}, which keeps the matching deterministic.</p>
 */
public record PlayerIdentity(
        String key,
        UUID uuid,
        String playerName,
        String xuid,
        String observedXuid,
        String requestedId,
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

    public PlayerIdentity(String key, UUID uuid, String playerName, String xuid, String observedXuid,
                          IdentityType type, IdentitySource source, boolean bedrock) {
        this(key, uuid, playerName, xuid, observedXuid, null, type, source, bedrock);
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

    /** The XUID when known, including a Geyser-observed XUID without Floodgate. */
    public String effectiveXuid() {
        return xuid != null && !xuid.isBlank() ? xuid : observedXuid;
    }

    /**
     * Canonical account ID for this identity: the XUID for Bedrock accounts, otherwise
     * the Mojang UUID. The player-chosen binding ID is stored in the same form, so a
     * lookup can compare both values without guessing.
     */
    public String canonicalId() {
        String effective = effectiveXuid();
        return effective != null && !effective.isBlank() ? effective : uuid.toString();
    }

    public static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        boolean dashed = value.length() == 36 && value.charAt(8) == '-';
        if (!(value.length() == 32 && value.chars().allMatch(PlayerIdentity::isHex))
                && !(dashed && value.chars().filter(Character::isLetterOrDigit).allMatch(PlayerIdentity::isHex))) {
            return null;
        }
        try {
            if (dashed) {
                return UUID.fromString(value.toLowerCase(Locale.ROOT));
            }
            return new UUID(Long.parseUnsignedLong(value.substring(0, 16), 16),
                    Long.parseUnsignedLong(value.substring(16), 16));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * True when the typed ID refers to this identity: XUID, Mojang UUID (dashed or
     * compact), or the exact player name (case-insensitive).
     */
    public boolean matchId(String id) {
        if (id == null) {
            return false;
        }
        String value = id.trim();
        if (value.isEmpty() || value.length() > 64) {
            return false;
        }
        String effective = effectiveXuid();
        if (effective != null && value.equalsIgnoreCase(effective)) {
            return true;
        }
        if (value.equalsIgnoreCase(uuid.toString())) {
            return true;
        }
        String compact = uuid.toString().replace("-", "");
        if (value.equalsIgnoreCase(compact)) {
            return true;
        }
        return playerName != null && value.equalsIgnoreCase(playerName);
    }

    /** The ID to show in the kick message: the UUID, which every Java and Bedrock account has. */
    public String bindHint() {
        return uuid.toString();
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * All keys under which an existing ban/binding could be stored. During a
     * transient Floodgate/Geyser outage a Bedrock player can be resolved as
     * {@code uuid:...}; checking the observed XUID and UUID as aliases prevents the
     * ban/binding from silently disappearing.
     */
    public List<String> lookupKeys() {
        List<String> keys = new ArrayList<>(3);
        keys.add(key);
        if (observedXuid != null && !observedXuid.isBlank()) {
            String xuidKey = "xuid:" + observedXuid;
            if (!keys.contains(xuidKey)) {
                keys.add(xuidKey);
            }
        }
        String uuidKey = "uuid:" + uuid;
        if (!keys.contains(uuidKey)) {
            keys.add(uuidKey);
        }
        return List.copyOf(keys);
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
        if (observedXuid != null && candidate.equalsIgnoreCase(observedXuid)) {
            return true;
        }
        if (candidate.toLowerCase(Locale.ROOT).startsWith("xuid:")) {
            String value = candidate.substring(5);
            return (xuid != null && value.equalsIgnoreCase(xuid))
                    || (observedXuid != null && value.equalsIgnoreCase(observedXuid));
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
