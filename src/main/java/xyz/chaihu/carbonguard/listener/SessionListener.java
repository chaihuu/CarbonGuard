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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import xyz.chaihu.carbonguard.CarbonGuardPlugin;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.util.IpUtil;

/** Converts pre-login reservations into online sessions and releases them on close. */
public final class SessionListener implements Listener {
    private final CarbonGuardPlugin plugin;
    private final LoginContexts contexts;

    public SessionListener(CarbonGuardPlugin plugin, LoginContexts contexts) {
        this.plugin = plugin;
        this.contexts = contexts;
    }

    /**
     * Releases a reservation when a connection closes before it ever joins. Using the
     * modern Paper connection event avoids the deprecated {@code PlayerLoginEvent}.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        plugin.data().sessions().release(event.getPlayerUniqueId());
        contexts.remove(event.getPlayerUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LoginContexts.Context context = contexts.get(player.getUniqueId()).orElse(null);
        if (context == null) {
            // A plugin reload or an externally injected connection did not pass through our
            // pre-login listener. Add a zero-limit reservation so online counts stay correct.
            String ip = IpUtil.normalize(player.getAddress() == null ? null : player.getAddress().getAddress());
            PlayerIdentity identity = plugin.identityResolver().resolve(player.getUniqueId(), player.getName());
            plugin.data().sessions().tryAcquire(ip, identity.key(), player.getUniqueId(), 0);
            plugin.data().sessions().confirm(player.getUniqueId());
            plugin.data().touchIdentity(identity, ip);
            return;
        }
        plugin.data().sessions().confirm(player.getUniqueId());
        plugin.data().touchIdentity(context.identity(), context.ip());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.data().sessions().release(event.getPlayer().getUniqueId());
        contexts.remove(event.getPlayer().getUniqueId());
    }
}
