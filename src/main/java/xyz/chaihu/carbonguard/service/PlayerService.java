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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.data.model.PlayerProfileView;
import xyz.chaihu.carbonguard.util.TimeUtil;

/** Read and maintenance operations for per-player history records. */
public final class PlayerService {
    private final DataStore data;
    private final Supplier<CarbonGuardConfig> configSupplier;
    private final Logger logger;

    public PlayerService(DataStore data, Supplier<CarbonGuardConfig> configSupplier, Logger logger) {
        this.data = data;
        this.configSupplier = configSupplier;
        this.logger = logger;
    }

    public CompletableFuture<PlayerProfileView> profile(PlayerIdentity identity) {
        return data.database().webAsync(connection -> {
            for (String key : identity.lookupKeys()) {
                Optional<PlayerProfileView> found = data.players().findExact(connection, key);
                if (found.isPresent()) {
                    return found.get();
                }
            }
            return null;
        });
    }

    public CompletableFuture<List<PlayerProfileView>> search(String query, int limit) {
        String q = query == null ? "" : query.trim();
        int bounded = Math.max(1, Math.min(100, limit));
        return data.database().webAsync(connection -> data.players().search(connection, q, bounded));
    }

    /**
     * One-time backfill of profiles for identities created before the table existed.
     * Called after startup so it never blocks the enable sequence.
     */
    public void backfillAsync() {
        if (!configSupplier.get().players().enable()) {
            return;
        }
        data.database().webAsync(connection -> data.players().backfillFromIdentities(connection,
                TimeUtil.now())).whenComplete((created, error) -> {
            if (error != null) {
                logger.log(Level.FINE, "玩家数据补齐失败", error);
            } else if (created > 0) {
                logger.info("玩家数据：已补齐 " + created + " 条历史记录");
            }
        });
    }

    /** Periodic maintenance: drops stale unbound players when retention is configured. */
    public void purgeAsync() {
        CarbonGuardConfig config = configSupplier.get();
        int retentionDays = config.players().retentionDays();
        if (!config.players().enable() || retentionDays <= 0) {
            return;
        }
        long cutoff = TimeUtil.now() - retentionDays * 86_400_000L;
        data.database().webAsync(connection -> data.players().deleteStale(connection, cutoff, 5_000))
                .whenComplete((removed, error) -> {
                    if (error != null) {
                        logger.log(Level.FINE, "玩家数据清理失败", error);
                    } else if (removed > 0) {
                        logger.info("玩家数据：已清理 " + removed + " 条超过 " + retentionDays + " 天未登录的记录");
                    }
                });
    }

    /** Human-readable summary for {@code /carbonguard status <player>}. */
    public static String describe(PlayerProfileView profile) {
        if (profile == null) {
            return "无记录";
        }
        String binding = profile.bound()
                ? profile.email() + " @ " + TimeUtil.display(profile.boundAt())
                : "未绑定";
        return "初次加入 " + TimeUtil.display(profile.firstJoin())
                + " | 绑定 " + binding
                + " | 登录 " + profile.loginCount() + " 次"
                + " | 最近 " + TimeUtil.display(profile.lastLogin())
                + " | 已游玩 " + (profile.playSeconds() / 60) + " 分钟";
    }
}
