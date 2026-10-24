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

package xyz.chaihu.carbonguard.data.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.chaihu.carbonguard.data.model.AssociationHit;
import xyz.chaihu.carbonguard.data.model.BanRecord;
import xyz.chaihu.carbonguard.util.TimeUtil;

/**
 * Read-only, preloaded ban and IP-association state for the login path.
 *
 * <p>All three indexes live in one immutable snapshot. Refresh and write-through both
 * swap the snapshot atomically, so a pre-login check cannot see bans and IP
 * associations from different refresh generations.</p>
 */
public final class BanCache {
    private final Object mutex = new Object();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private long version;

    public long version() {
        synchronized (mutex) {
            return version;
        }
    }

    public boolean replaceAllIfUnchanged(List<BanRecord> bans, List<AssociationHit> associations,
                                         long expectedVersion) {
        Map<String, BanRecord> identity = new HashMap<>();
        Map<Long, BanRecord> byId = new HashMap<>();
        for (BanRecord ban : bans) {
            if (!ban.effectiveActive()) {
                continue;
            }
            BanRecord existing = identity.get(ban.identityKey());
            if (existing == null || existing.createdAt() < ban.createdAt()) {
                identity.put(ban.identityKey(), ban);
            }
            byId.put(ban.id(), ban);
        }
        Map<String, AssociationHit> ip = new HashMap<>();
        long now = TimeUtil.now();
        for (AssociationHit hit : associations) {
            if (!hit.activeAt(now) || !byId.containsKey(hit.banId())) {
                continue;
            }
            AssociationHit existing = ip.get(hit.ip());
            if (existing == null || existing.recordedAt() < hit.recordedAt()) {
                ip.put(hit.ip(), hit);
            }
        }
        synchronized (mutex) {
            if (version != expectedVersion) {
                return false;
            }
            snapshot = new Snapshot(Map.copyOf(identity), Map.copyOf(byId), Map.copyOf(ip));
            return true;
        }
    }

    public Optional<BanRecord> directBan(String identityKey) {
        BanRecord ban = snapshot.byIdentity().get(identityKey);
        if (ban == null || !ban.effectiveActive()) {
            return Optional.empty();
        }
        return Optional.of(ban);
    }

    public Optional<AssociationHit> association(String ip) {
        AssociationHit hit = snapshot.byIp().get(ip);
        if (hit == null || !hit.activeAt(TimeUtil.now())) {
            return Optional.empty();
        }
        return Optional.of(hit);
    }

    public void addBan(BanRecord ban, List<AssociationHit> associations) {
        if (!ban.effectiveActive()) {
            return;
        }
        synchronized (mutex) {
            Snapshot current = snapshot;
            Map<Long, BanRecord> byId = new HashMap<>(current.byId());
            Map<String, BanRecord> identity = new HashMap<>(current.byIdentity());
            Map<String, AssociationHit> byIp = new HashMap<>(current.byIp());
            byId.put(ban.id(), ban);
            BanRecord old = identity.get(ban.identityKey());
            if (old == null || old.createdAt() <= ban.createdAt()) {
                identity.put(ban.identityKey(), ban);
            }
            long now = TimeUtil.now();
            for (AssociationHit hit : associations) {
                if (!hit.activeAt(now)) {
                    continue;
                }
                AssociationHit existing = byIp.get(hit.ip());
                if (existing == null || existing.recordedAt() <= hit.recordedAt()) {
                    byIp.put(hit.ip(), hit);
                }
            }
            snapshot = new Snapshot(Map.copyOf(identity), Map.copyOf(byId), Map.copyOf(byIp));
            version++;
        }
    }

    public void removeBan(long banId) {
        synchronized (mutex) {
            Snapshot current = snapshot;
            BanRecord removed = current.byId().get(banId);
            if (removed == null) {
                return;
            }
            Map<Long, BanRecord> byId = new HashMap<>(current.byId());
            byId.remove(banId);
            Map<String, BanRecord> identity = new HashMap<>();
            for (BanRecord candidate : byId.values()) {
                if (!candidate.effectiveActive()) {
                    continue;
                }
                BanRecord existing = identity.get(candidate.identityKey());
                if (existing == null || existing.createdAt() < candidate.createdAt()) {
                    identity.put(candidate.identityKey(), candidate);
                }
            }
            Map<String, AssociationHit> byIp = new HashMap<>(current.byIp());
            byIp.entrySet().removeIf(entry -> entry.getValue().banId() == banId);
            snapshot = new Snapshot(Map.copyOf(identity), Map.copyOf(byId), Map.copyOf(byIp));
            version++;
        }
    }

    public int associationCount() {
        return snapshot.byIp().size();
    }

    public int banCount() {
        return snapshot.byId().size();
    }

    private record Snapshot(Map<String, BanRecord> byIdentity, Map<Long, BanRecord> byId,
                            Map<String, AssociationHit> byIp) {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of());
    }
}
