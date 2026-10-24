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

import java.util.List;
import java.util.function.Supplier;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.util.CidrMatcher;

/** Compiled CIDR/player whitelists. Rebuilt atomically on configuration reload. */
public final class AccessPolicy {
    private final Supplier<CarbonGuardConfig> configSupplier;
    private volatile Rules rules;

    public AccessPolicy(Supplier<CarbonGuardConfig> configSupplier) {
        this.configSupplier = configSupplier;
        refresh();
    }

    public void refresh() {
        CarbonGuardConfig config = configSupplier.get();
        rules = new Rules(
                config.whitelist().enable(),
                CidrMatcher.of(config.whitelist().ips()),
                List.copyOf(config.whitelist().players()),
                CidrMatcher.of(config.ipLimit().whitelistIps()),
                List.copyOf(config.ipLimit().whitelistPlayers())
        );
    }

    public boolean globalWhitelisted(PlayerIdentity identity, String ip) {
        Rules current = rules;
        if (!current.globalEnabled) {
            return false;
        }
        return current.globalIps.matches(ip) || matchesPlayer(current.globalPlayers, identity);
    }

    public boolean ipLimitWhitelisted(PlayerIdentity identity, String ip) {
        if (globalWhitelisted(identity, ip)) {
            return true;
        }
        Rules current = rules;
        return current.ipLimitIps.matches(ip) || matchesPlayer(current.ipLimitPlayers, identity);
    }

    private static boolean matchesPlayer(List<String> entries, PlayerIdentity identity) {
        for (String entry : entries) {
            if (identity.matchesWhitelistToken(entry)) {
                return true;
            }
        }
        return false;
    }

    private record Rules(boolean globalEnabled, CidrMatcher globalIps, List<String> globalPlayers,
                         CidrMatcher ipLimitIps, List<String> ipLimitPlayers) {
    }
}
