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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.AssociationHit;
import xyz.chaihu.carbonguard.data.model.BanRecord;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.util.TimeUtil;

/** Applies bans and writes the associated current/historical IPs atomically. */
public final class BanService {
    private final DataStore data;
    private final Supplier<CarbonGuardConfig> configSupplier;

    public BanService(DataStore data, Supplier<CarbonGuardConfig> configSupplier) {
        this.data = data;
        this.configSupplier = configSupplier;
    }

    public CompletableFuture<BanRecord> ban(PlayerIdentity target, String reason, Duration duration,
                                            String actor, String currentIp) {
        long now = TimeUtil.now();
        long expiresAt = duration == null || duration.isZero() || duration.isNegative()
                ? 0 : now + duration.toMillis();
        long ttlHours = configSupplier.get().ban().ipAssociation().ttlHours();
        String safeReason = reason == null || reason.isBlank() ? "未提供原因" : reason.trim();

        return data.database().writeTransaction(connection -> {
            String ip = currentIp;
            if (ip == null || ip.isBlank()) {
                List<String> known = data.bans().knownIps(connection, target.key());
                ip = known.isEmpty() ? "0.0.0.0" : known.get(0);
            }
            data.identities().upsert(connection, target, ip, now);
            long banId = data.bans().insertBan(connection, target.key(), target.playerName(),
                    safeReason, actor, now, expiresAt);
            List<String> associated = data.bans().associateIps(connection, banId, target.key(),
                    currentIp, now, ttlHours);
            return new InsertedBan(new BanRecord(banId, target.key(), target.playerName(), safeReason,
                    actor, now, expiresAt, true, 0, null), associated,
                    ttlHours <= 0 ? 0 : now + ttlHours * 3_600_000L);
        }).thenApply(inserted -> {
            List<AssociationHit> hits = inserted.ips().stream()
                    .map(ip -> new AssociationHit(0, inserted.ban().id(), ip, target.key(),
                            safeReason, target.playerName(), now, inserted.associationExpiry(),
                            inserted.ban().expiresAt()))
                    .toList();
            data.banCache().addBan(inserted.ban(), hits);
            return inserted.ban();
        });
    }

    public CompletableFuture<Boolean> unban(long banId, String actor) {
        return data.database().writeTransaction(connection -> {
            boolean changed = data.bans().deactivateBan(connection, banId, actor, TimeUtil.now());
            return changed;
        }).thenApply(changed -> {
            if (changed) {
                data.banCache().removeBan(banId);
            }
            return changed;
        });
    }

    public CompletableFuture<Integer> unbanIdentity(String identityKey, String actor) {
        long now = TimeUtil.now();
        return data.database().writeTransaction(connection -> {
            List<Long> ids = data.bans().bansForIdentity(connection, identityKey).stream()
                    .filter(BanRecord::effectiveActive)
                    .map(BanRecord::id)
                    .toList();
            int changed = data.bans().deactivateAllForIdentity(connection, identityKey, actor, now);
            return new UnbanResult(changed, ids);
        }).thenApply(result -> {
            result.ids().forEach(data.banCache()::removeBan);
            return result.count();
        });
    }

    public CompletableFuture<Optional<BanRecord>> activeBanFor(String identityKey) {
        return data.database().webAsync(connection ->
                data.bans().bansForIdentity(connection, identityKey).stream()
                        .filter(BanRecord::effectiveActive)
                        .findFirst());
    }

    public CompletableFuture<List<BanRecord>> historyFor(String identityKey) {
        return data.database().webAsync(connection -> data.bans().bansForIdentity(connection, identityKey));
    }

    private record InsertedBan(BanRecord ban, List<String> ips, long associationExpiry) {
    }

    private record UnbanResult(int count, List<Long> ids) {
    }
}
