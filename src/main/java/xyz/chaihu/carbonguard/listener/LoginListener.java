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

import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import xyz.chaihu.carbonguard.CarbonGuardPlugin;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.service.LoginDecision;
import xyz.chaihu.carbonguard.util.IpUtil;

/**
 * Login interception. This runs inside an async pre-login event, so it only touches
 * in-memory caches and quick in-memory token generation. Database writes are queued.
 */
public final class LoginListener implements Listener {
    private final CarbonGuardPlugin plugin;
    private final LoginContexts contexts;

    public LoginListener(CarbonGuardPlugin plugin, LoginContexts contexts) {
        this.plugin = plugin;
        this.contexts = contexts;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        PlayerIdentity identity = plugin.identityResolver().resolve(event.getUniqueId(), event.getName());
        String ip = IpUtil.normalize(event.getAddress());
        LoginDecision decision;
        try {
            decision = plugin.loginGate().evaluate(identity, ip);
        } catch (Throwable error) {
            plugin.getLogger().log(Level.SEVERE, "登录判定异常，已 fail-open 放行 " + identity.playerName(), error);
            decision = LoginDecision.allow("ALLOWED", "gate_error=" + error.getClass().getSimpleName(), java.util.List.of());
            plugin.data().recordLogin(identity, ip, decision.outcome(), decision.detail());
            contexts.put(event.getUniqueId(), new LoginContexts.Context(identity, ip));
            return;
        }

        plugin.data().recordLogin(identity, ip, decision.outcome(), decision.detail());
        if (!decision.allowed()) {
            plugin.debug("拒绝登录 " + identity.playerName() + " (" + identity.key() + ") ip=" + ip
                    + " reason=" + decision.detail());
            event.disallow(decision.banStyle()
                            ? AsyncPlayerPreLoginEvent.Result.KICK_BANNED
                            : AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    decision.message());
            return;
        }

        for (LoginDecision.ReviewAlert alert : decision.alerts()) {
            plugin.data().queueReview(alert.type(), identity, ip, alert.banId(), alert.reason());
        }
        if (decision.detail().contains("ip_association_log_only=true")) {
            plugin.getLogger().info("IP 关联命中(LOG_ONLY): " + identity.playerName()
                    + " ip=" + ip + " detail=" + decision.detail());
        }
        if (!decision.alerts().isEmpty()) {
            plugin.getLogger().info("待审登录: " + identity.playerName() + " ip=" + ip
                    + " detail=" + decision.detail());
        }
        contexts.put(event.getUniqueId(), new LoginContexts.Context(identity, ip));
        plugin.debug("允许登录 " + identity.playerName() + " (" + identity.key() + ") ip=" + ip
                + " source=" + identity.source() + " outcome=" + decision.outcome());
    }
}
