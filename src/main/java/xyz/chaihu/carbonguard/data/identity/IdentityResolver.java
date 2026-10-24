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

package xyz.chaihu.carbonguard.data.identity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;

/**
 * Resolves the stable business identity at pre-login time.
 *
 * <p>Priority: Floodgate XUID -> Geyser-detected Bedrock UUID fallback -> Java UUID.
 * Both integrations are optional and are probed defensively; no XUID is derived from
 * a Bukkit UUID.</p>
 */
public final class IdentityResolver {
    private final Logger logger;
    private final boolean floodgatePresent;
    private final boolean geyserPresent;
    /**
     * Per-UUID verdict cache. Pre-login resolution runs on connection threads and
     * asks Floodgate/Geyser on every attempt; caching removes repeated reflection
     * and map lookups for reconnecting players while still re-checking unknown
     * UUIDs.
     */
    private final Map<UUID, CachedVerdict> verdictCache = new ConcurrentHashMap<>();
    private static final int VERDICT_CACHE_MAX = 8_192;

    public IdentityResolver(Logger logger) {
        this.logger = logger;
        this.floodgatePresent = pluginEnabled("floodgate") && classPresent("org.geysermc.floodgate.api.FloodgateApi");
        this.geyserPresent = (pluginEnabled("Geyser-Spigot") || pluginEnabled("Geyser"))
                && classPresent("org.geysermc.geyser.api.GeyserApi");
    }

    public PlayerIdentity resolve(UUID uuid, String playerName) {
        String name = playerName == null || playerName.isBlank() ? uuid.toString() : playerName;
        CachedVerdict cached = verdictCache.get(uuid);
        if (cached != null) {
            return cached.toIdentity(uuid, name);
        }
        PlayerIdentity identity = resolveUncached(uuid, name);
        remember(uuid, identity);
        return identity;
    }

    private PlayerIdentity resolveUncached(UUID uuid, String name) {
        if (floodgatePresent) {
            try {
                FloodgateBridge.Lookup lookup = FloodgateBridge.lookup(uuid);
                if (lookup.bedrock()) {
                    if (lookup.xuid() != null && !lookup.xuid().isBlank()) {
                        String observed = geyserPresent ? GeyserBridge.observedXuid(uuid) : null;
                        return PlayerIdentity.xuid(uuid, name, lookup.xuid(), observed);
                    }
                    // Floodgate knows it is Bedrock but could not expose an XUID. Keep the
                    // UUID fallback rather than inventing an identity key.
                    return PlayerIdentity.fallback(uuid, name,
                            geyserPresent ? GeyserBridge.observedXuid(uuid) : null);
                }
            } catch (LinkageError | RuntimeException e) {
                logger.log(Level.WARNING, "Floodgate 身份读取失败，尝试 Geyser/UUID 降级", e);
            }
        }
        if (geyserPresent) {
            try {
                if (GeyserBridge.isBedrock(uuid)) {
                    return PlayerIdentity.fallback(uuid, name, GeyserBridge.observedXuid(uuid));
                }
            } catch (LinkageError | RuntimeException e) {
                logger.log(Level.FINE, "Geyser 身份判定失败，按 Java 玩家处理", e);
            }
        }
        return PlayerIdentity.javaPlayer(uuid, name);
    }

    private void remember(UUID uuid, PlayerIdentity identity) {
        if (verdictCache.size() >= VERDICT_CACHE_MAX) {
            verdictCache.clear();
        }
        verdictCache.put(uuid, CachedVerdict.of(identity));
    }

    /** Clears the verdict cache, e.g. after a reload when Geyser/Floodgate changed. */
    public void invalidateCache() {
        verdictCache.clear();
    }

    private record CachedVerdict(Kind kind, String xuid, String observedXuid) {
        enum Kind { XUID, BEDROCK_FALLBACK, JAVA }

        static CachedVerdict of(PlayerIdentity identity) {
            return switch (identity.source()) {
                case FLOODGATE -> new CachedVerdict(Kind.XUID, identity.xuid(), identity.observedXuid());
                case UUID_FALLBACK -> new CachedVerdict(Kind.BEDROCK_FALLBACK, null, identity.observedXuid());
                case JAVA -> new CachedVerdict(Kind.JAVA, null, null);
            };
        }

        PlayerIdentity toIdentity(UUID uuid, String name) {
            return switch (kind) {
                case XUID -> PlayerIdentity.xuid(uuid, name, xuid, observedXuid);
                case BEDROCK_FALLBACK -> PlayerIdentity.fallback(uuid, name, observedXuid);
                case JAVA -> PlayerIdentity.javaPlayer(uuid, name);
            };
        }
    }

    public boolean floodgatePresent() {
        return floodgatePresent;
    }

    public boolean geyserPresent() {
        return geyserPresent;
    }

    /**
     * True when the UUID is a Bedrock player currently connected through Geyser.
     * Used by the per-IP limit exemption; returns false on any integration failure.
     */
    public boolean bedrockOnline(UUID uuid) {
        if (uuid == null || !geyserPresent) {
            return false;
        }
        try {
            return GeyserBridge.online(uuid);
        } catch (LinkageError | RuntimeException e) {
            logger.log(Level.FINE, "Geyser 在线状态查询失败", e);
            return false;
        }
    }

    public String integrationSummary() {
        return "Floodgate=" + (floodgatePresent ? "可用" : "未安装")
                + ", Geyser=" + (geyserPresent ? "可用" : "未安装");
    }

    private static boolean pluginEnabled(String name) {
        try {
            return Bukkit.getPluginManager().isPluginEnabled(name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, IdentityResolver.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
