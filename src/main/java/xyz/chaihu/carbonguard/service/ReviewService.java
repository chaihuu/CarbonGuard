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
import java.util.function.Supplier;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.ReviewAllowEntry;
import xyz.chaihu.carbonguard.data.model.ReviewRecord;
import xyz.chaihu.carbonguard.data.model.ReviewStatus;
import xyz.chaihu.carbonguard.util.TimeUtil;

public final class ReviewService {
    private final DataStore data;
    private final Supplier<CarbonGuardConfig> configSupplier;

    public ReviewService(DataStore data, Supplier<CarbonGuardConfig> configSupplier) {
        this.data = data;
        this.configSupplier = configSupplier;
    }

    public CompletableFuture<List<ReviewRecord>> list(ReviewStatus status, int limit) {
        int bounded = Math.max(1, Math.min(200, limit));
        return data.database().webAsync(connection -> data.reviews().list(connection, status, bounded));
    }

    public CompletableFuture<ReviewRecord> decide(long id, ReviewStatus status, String actor) {
        if (status == ReviewStatus.PENDING) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("status 必须是 APPROVED 或 REJECTED"));
        }
        long now = TimeUtil.now();
        int approvalTtlHours = Math.max(0, configSupplier.get().review().approvalTtlHours());
        return data.database().writeTransaction(connection -> {
            boolean changed = data.reviews().updateStatus(connection, id, status, actor, now);
            if (!changed) {
                throw new IllegalStateException("待审记录不存在或已被处理");
            }
            ReviewRecord review = data.reviews().find(connection, id).orElseThrow();
            ReviewAllowEntry allow = null;
            if (review.ip() != null && !review.ip().isBlank()) {
                if (status == ReviewStatus.APPROVED && approvalTtlHours > 0) {
                    long expiresAt = now + approvalTtlHours * 3_600_000L;
                    data.reviews().allow(connection, review.type(), review.identityKey(), review.ip(),
                            now, expiresAt, actor);
                    allow = new ReviewAllowEntry(review.type(), review.identityKey(), review.ip(),
                            now, expiresAt, actor);
                } else {
                    data.reviews().removeAllow(connection, review.type(), review.identityKey(), review.ip());
                }
            }
            return new Decision(review, allow);
        }).thenApply(decision -> {
            if (decision.allow() != null) {
                data.reviewCache().put(decision.allow());
            } else {
                data.reviewCache().remove(decision.review().type(),
                        decision.review().identityKey(), decision.review().ip());
            }
            return decision.review();
        });
    }

    private record Decision(ReviewRecord review, ReviewAllowEntry allow) {
    }
}
