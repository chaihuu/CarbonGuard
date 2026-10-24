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

import java.util.UUID;
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

    public IdentityResolver(Logger logger) {
        this.logger = logger;
        this.floodgatePresent = pluginEnabled("floodgate") && classPresent("org.geysermc.floodgate.api.FloodgateApi");
        this.geyserPresent = (pluginEnabled("Geyser-Spigot") || pluginEnabled("Geyser"))
                && classPresent("org.geysermc.geyser.api.GeyserApi");
    }

    public PlayerIdentity resolve(UUID uuid, String playerName) {
        String name = playerName == null || playerName.isBlank() ? uuid.toString() : playerName;
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

    public boolean floodgatePresent() {
        return floodgatePresent;
    }

    public boolean geyserPresent() {
        return geyserPresent;
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
