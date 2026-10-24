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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.Database;
import xyz.chaihu.carbonguard.data.model.BanView;
import xyz.chaihu.carbonguard.data.model.BindingView;
import xyz.chaihu.carbonguard.data.model.StatsSnapshot;
import xyz.chaihu.carbonguard.util.TimeUtil;

/** Read models used by the admin HTTP endpoints. */
public final class AdminService {
    private final DataStore data;
    private final CarbonGuardConfig config;
    private final BindTicketService tickets;
    private final Semaphore adminSlots;

    public AdminService(DataStore data, CarbonGuardConfig config, BindTicketService tickets) {
        this.data = data;
        this.config = config;
        this.tickets = tickets;
        // Leave at least one web-pool connection for the public ticket/binding flow.
        this.adminSlots = new Semaphore(Math.max(1, config.storage().pool().webMaximumSize() - 1), true);
    }

    public CompletableFuture<StatsSnapshot> stats() {
        return adminQuery(connection -> data.stats().snapshot(connection, TimeUtil.now(),
                Math.min(7, config.stats().retentionDays()), data.sessions().onlineCount()));
    }

    public CompletableFuture<List<BanView>> searchBans(String query, int limit) {
        String q = query == null ? "" : query.trim();
        int bounded = Math.max(1, Math.min(100, limit));
        return adminQuery(connection -> data.bans().search(connection, q, bounded, TimeUtil.now()));
    }

    public CompletableFuture<List<BindingView>> searchBindings(String query, int limit) {
        String q = query == null ? "" : query.trim();
        int bounded = Math.max(1, Math.min(100, limit));
        return adminQuery(connection -> data.bindings().search(connection, q, bounded));
    }

    private <T> CompletableFuture<T> adminQuery(Database.SqlCall<T> call) {
        if (!adminSlots.tryAcquire()) {
            return CompletableFuture.failedFuture(new IllegalStateException("管理查询繁忙，请稍后重试"));
        }
        try {
            return data.database().webAsync(call).whenComplete((result, error) -> adminSlots.release());
        } catch (Throwable error) {
            adminSlots.release();
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Boolean> unbind(String identityKey, String actor) {
        if (identityKey == null || identityKey.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("identity 不能为空"));
        }
        String key = identityKey.trim();
        return data.database().writeTransaction(connection -> {
            java.util.Optional<xyz.chaihu.carbonguard.data.model.PlayerIdentity> identity =
                    data.identities().findByKey(connection, key);
            java.util.List<String> keys = identity
                    .map(xyz.chaihu.carbonguard.data.model.PlayerIdentity::lookupKeys)
                    .orElseGet(() -> java.util.List.of(key));
            boolean removed = false;
            for (String candidate : keys) {
                if (data.bindings().delete(connection, candidate)) {
                    removed = true;
                }
                data.verification().deleteForIdentity(connection, candidate);
            }
            return new UnbindResult(removed, keys);
        }).thenApply(result -> {
            if (result.removed()) {
                for (String candidate : result.keys()) {
                    data.bindingCache().remove(candidate);
                    tickets.invalidateIdentity(candidate);
                }
            }
            return result.removed();
        });
    }

    private record UnbindResult(boolean removed, List<String> keys) {
    }

    public CarbonGuardConfig config() {
        return config;
    }
}
