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

package xyz.chaihu.carbonguard.listener;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import xyz.chaihu.carbonguard.CarbonGuardPlugin;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.service.LoginDecision;
import xyz.chaihu.carbonguard.util.IpUtil;
import xyz.chaihu.carbonguard.util.TimeUtil;

/** Converts pre-login reservations into online sessions and releases them on close. */
public final class SessionListener implements Listener {
    private final CarbonGuardPlugin plugin;
    private final LoginContexts contexts;
    /** Join timestamps used to accumulate per-player play time on quit. */
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();

    public SessionListener(CarbonGuardPlugin plugin, LoginContexts contexts) {
        this.plugin = plugin;
        this.contexts = contexts;
    }

    /**
     * Releases only the reservation that belongs to this connection. Joined players
     * have no context (it is removed on join), so a delayed close event for an old
     * connection cannot delete a newer session lease.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        LoginContexts.Context context = contexts.get(event.getPlayerUniqueId()).orElse(null);
        if (context == null) {
            return;
        }
        String closeIp = event.getIpAddress() == null ? null
                : IpUtil.networkKey(event.getIpAddress());
        if (closeIp != null && !closeIp.equals(IpUtil.networkKey(context.ip()))) {
            // A delayed close from an old network must not release a newer session.
            return;
        }
        plugin.data().sessions().release(event.getPlayerUniqueId(), context.sessionId());
        contexts.remove(event.getPlayerUniqueId(), context);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LoginContexts.Context context = contexts.get(player.getUniqueId()).orElse(null);
        if (context == null) {
            // A plugin reload or an externally injected connection did not pass through
            // our pre-login listener. Re-run the gate instead of silently granting a
            // zero-limit session.
            String ip = IpUtil.normalize(player.getAddress() == null ? null
                    : player.getAddress().getAddress());
            PlayerIdentity identity = plugin.identityResolver().resolve(player.getUniqueId(), player.getName());
            LoginDecision decision;
            try {
                decision = plugin.loginGate().evaluate(identity, ip);
            } catch (Throwable error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "补登录校验异常，已 fail-closed 踢出 " + player.getName(), error);
                player.kick(plugin.messages().component("kick.internal-error"));
                return;
            }
            if (!decision.allowed()) {
                player.kick(decision.message());
                return;
            }
            plugin.data().sessions().confirmOrCreate(ip, identity.key(), player.getUniqueId(),
                    decision.sessionId());
            for (LoginDecision.ReviewAlert alert : decision.alerts()) {
                plugin.data().queueReview(alert.type(), identity, ip, alert.banId(), alert.reason());
            }
            plugin.data().touchIdentity(identity, ip);
            plugin.data().recordJoin(identity, ip, TimeUtil.now());
            joinTimes.put(player.getUniqueId(), System.currentTimeMillis());
            return;
        }

        boolean confirmed = plugin.data().sessions().confirm(player.getUniqueId(), context.sessionId());
        if (!confirmed) {
            // The 90-second reservation expired while the client was still connecting.
            // Re-register the online session rather than under-counting it.
            plugin.data().sessions().confirmOrCreate(context.ip(), context.identity().key(),
                    player.getUniqueId(), context.sessionId());
        }
        plugin.data().touchIdentity(context.identity(), context.ip());
        plugin.data().recordJoin(context.identity(), context.ip(), TimeUtil.now());
        joinTimes.put(player.getUniqueId(), System.currentTimeMillis());
        contexts.remove(player.getUniqueId(), context);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.data().sessions().releaseActive(player.getUniqueId());
        LoginContexts.Context context = contexts.get(player.getUniqueId()).orElse(null);
        PlayerIdentity identity = context != null ? context.identity()
                : plugin.identityResolver().resolve(player.getUniqueId(), player.getName());
        Long joinedAt = joinTimes.remove(player.getUniqueId());
        if (joinedAt != null) {
            plugin.data().recordPlaytime(identity, System.currentTimeMillis() - joinedAt);
        }
        contexts.remove(player.getUniqueId());
    }
}
