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
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.BanView;
import xyz.chaihu.carbonguard.data.model.BindingView;
import xyz.chaihu.carbonguard.data.model.ReviewRecord;
import xyz.chaihu.carbonguard.data.model.ReviewStatus;
import xyz.chaihu.carbonguard.data.model.StatsSnapshot;
import xyz.chaihu.carbonguard.util.TimeUtil;

/** Read models used by the admin HTTP endpoints. */
public final class AdminService {
    private final DataStore data;
    private final CarbonGuardConfig config;

    public AdminService(DataStore data, CarbonGuardConfig config) {
        this.data = data;
        this.config = config;
    }

    public CompletableFuture<StatsSnapshot> stats() {
        return data.database().webAsync(connection -> data.stats().snapshot(connection, TimeUtil.now(),
                Math.min(7, config.stats().retentionDays()), data.sessions().onlineCount()));
    }

    public CompletableFuture<List<BanView>> searchBans(String query, int limit) {
        String q = query == null ? "" : query.trim();
        int bounded = Math.max(1, Math.min(100, limit));
        return data.database().webAsync(connection ->
                data.bans().search(connection, q, bounded, TimeUtil.now()));
    }

    public CompletableFuture<List<BindingView>> searchBindings(String query, int limit) {
        String q = query == null ? "" : query.trim();
        int bounded = Math.max(1, Math.min(100, limit));
        return data.database().webAsync(connection -> data.bindings().search(connection, q, bounded));
    }

    public CompletableFuture<List<ReviewRecord>> pendingReviews(int limit) {
        return data.database().webAsync(connection -> data.reviews().list(connection,
                ReviewStatus.PENDING, Math.max(1, Math.min(200, limit))));
    }

    public CarbonGuardConfig config() {
        return config;
    }
}
