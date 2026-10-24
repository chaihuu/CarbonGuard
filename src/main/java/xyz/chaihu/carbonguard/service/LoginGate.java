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

package xyz.chaihu.carbonguard.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.config.IpLimitMode;
import xyz.chaihu.carbonguard.config.Messages;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.cache.SessionRegistry;
import xyz.chaihu.carbonguard.data.model.AssociationHit;
import xyz.chaihu.carbonguard.data.model.BanRecord;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.data.model.ReviewType;
import xyz.chaihu.carbonguard.service.LoginDecision.ReviewAlert;
import xyz.chaihu.carbonguard.util.TimeUtil;

/**
 * Pure, cache-only login evaluation. It must never call JDBC: this method runs inside
 * {@code AsyncPlayerPreLoginEvent}, where every handler must return before the player
 * can finish logging in.
 */
public final class LoginGate {
    private final DataStore data;
    private final SessionRegistry sessions;
    private final AccessPolicy accessPolicy;
    private final BindTicketService tickets;
    private final Supplier<Messages> messages;
    private final Supplier<CarbonGuardConfig> configSupplier;
    private final BooleanSupplier bindingOperational;

    public LoginGate(DataStore data, SessionRegistry sessions, AccessPolicy accessPolicy,
                     BindTicketService tickets, Supplier<Messages> messages,
                     Supplier<CarbonGuardConfig> configSupplier, BooleanSupplier bindingOperational) {
        this.data = data;
        this.sessions = sessions;
        this.accessPolicy = accessPolicy;
        this.tickets = tickets;
        this.messages = messages;
        this.configSupplier = configSupplier;
        this.bindingOperational = bindingOperational;
    }

    public LoginDecision evaluate(PlayerIdentity identity, String ip) {
        CarbonGuardConfig config = configSupplier.get();
        boolean globalBypass = accessPolicy.globalWhitelisted(identity, ip);
        List<ReviewAlert> alerts = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (config.ban().enable() && config.ban().idUidXuid().enable()) {
            Optional<BanRecord> ban = data.banCache().directBan(identity.key());
            if (ban.isPresent()) {
                BanRecord record = ban.get();
                notes.add("direct_ban=" + record.id());
                return LoginDecision.deny(messages.get().component("kick.banned", Map.of(
                        "reason", safe(record.reason()),
                        "expires", record.permanent() ? "永久" : TimeUtil.display(record.expiresAt()),
                        "banned_by", safe(record.createdBy())
                )), String.join(";", notes), true);
            }
        }

        if (bindingEnforced(config) && !globalBypass && !data.bindingCache().isBound(identity.key())) {
            if (!data.bindingCache().ready()) {
                notes.add("binding_cache_not_ready=fail_open");
            } else {
                String url = config.web().publicUrl();
                Optional<BindTicketService.Ticket> ticket = tickets.issue(identity, ip);
                if (ticket.isPresent()) {
                    url = tickets.urlFor(ticket.get().raw());
                }
                notes.add("unbound=true");
                return LoginDecision.deny(messages.get().component("kick.unbound", Map.of(
                        "url", url,
                        "player", safe(identity.playerName()),
                        "identity", safe(identity.displayIdentity())
                )), String.join(";", notes), false);
            }
        }

        if (config.ban().enable() && config.ban().ipAssociation().enable() && !globalBypass) {
            Optional<AssociationHit> hit = data.banCache().association(ip);
            if (hit.isPresent()) {
                AssociationHit association = hit.get();
                notes.add("ip_association=" + association.banId() + "@" + association.ip());
                switch (config.ban().ipAssociation().action()) {
                    case REJECT -> {
                        return LoginDecision.deny(messages.get().component("kick.ip-association", Map.of(
                                "reason", safe(association.reason()),
                                "banned_player", safe(association.bannedPlayer()),
                                "expires", association.banExpiresAt() <= 0 ? "永久"
                                        : TimeUtil.display(association.banExpiresAt()),
                                "ip", ip
                        )), String.join(";", notes), true);
                    }
                    case PENDING_REVIEW -> alerts.add(new ReviewAlert(ReviewType.IP_ASSOCIATION,
                            association.banId(), association.reason()));
                    case LOG_ONLY -> notes.add("ip_association_log_only=true");
                }
            }
        }

        boolean limitWhitelisted = accessPolicy.ipLimitWhitelisted(identity, ip);
        int hardLimit = 0;
        if (config.ipLimit().enable() && !limitWhitelisted) {
            hardLimit = config.ipLimit().hardLimit();
        }
        SessionRegistry.AcquireResult acquire = sessions.tryAcquire(ip, identity.key(), identity.uuid(), hardLimit);
        if (!acquire.allowed()) {
            notes.add("ip_limit=" + acquire.activeCount() + "/" + hardLimit);
            if (config.ipLimit().mode() == IpLimitMode.REJECT) {
                return LoginDecision.deny(messages.get().component("kick.ip-limit", Map.of(
                        "limit", Integer.toString(config.ipLimit().maxAccountsPerIp()),
                        "grace", Integer.toString(config.ipLimit().graceThreshold()),
                        "hard_limit", Integer.toString(hardLimit),
                        "active", Integer.toString(acquire.activeCount()),
                        "ip", ip
                )), String.join(";", notes), false);
            }
            // WARN_ONLY must still be able to enter. Reserve without a cap for this player.
            sessions.tryAcquire(ip, identity.key(), identity.uuid(), 0);
            alerts.add(new ReviewAlert(ReviewType.IP_LIMIT, null,
                    "IP " + ip + " 活跃账号 " + acquire.activeCount() + "，硬上限 " + hardLimit));
            notes.add("ip_limit_warn_only=true");
        } else if (config.ipLimit().enable() && !limitWhitelisted) {
            notes.add("ip_limit=" + acquire.activeCount() + "/" + hardLimit);
        }
        if (globalBypass) {
            notes.add("global_whitelist=true");
        }

        String outcome = alerts.isEmpty() ? "ALLOWED" : "WARN_ONLY";
        return LoginDecision.allow(outcome, String.join(";", notes), alerts);
    }

    private boolean bindingEnforced(CarbonGuardConfig config) {
        return config.emailVerify().enable()
                && config.emailVerify().requireBinding()
                && bindingOperational.getAsBoolean();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
