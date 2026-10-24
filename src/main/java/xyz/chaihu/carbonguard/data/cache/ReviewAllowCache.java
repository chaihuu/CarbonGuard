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
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.data.model.ReviewAllowEntry;
import xyz.chaihu.carbonguard.data.model.ReviewType;
import xyz.chaihu.carbonguard.util.IpUtil;
import xyz.chaihu.carbonguard.util.TimeUtil;

/**
 * Preloaded review approvals. A pre-login check must never query JDBC, so approved
 * (type, identity, ip) tuples are mirrored from the database and updated write-through.
 */
public final class ReviewAllowCache {
    private final Object mutex = new Object();
    private volatile Map<String, ReviewAllowEntry> entries = Map.of();
    private long version;

    public long version() {
        synchronized (mutex) {
            return version;
        }
    }

    public boolean replaceAllIfUnchanged(List<ReviewAllowEntry> loaded, long expectedVersion) {
        Map<String, ReviewAllowEntry> next = new HashMap<>();
        long now = TimeUtil.now();
        for (ReviewAllowEntry entry : loaded) {
            if (entry.activeAt(now)) {
                next.put(key(entry.type(), entry.identityKey(), entry.ip()), entry);
            }
        }
        synchronized (mutex) {
            if (version != expectedVersion) {
                return false;
            }
            entries = Map.copyOf(next);
            return true;
        }
    }

    public boolean allowed(ReviewType type, PlayerIdentity identity, String ip) {
        Map<String, ReviewAllowEntry> current = entries;
        long now = TimeUtil.now();
        for (String identityKey : identity.lookupKeys()) {
            ReviewAllowEntry entry = current.get(key(type, identityKey, ip));
            if (entry != null && entry.activeAt(now)) {
                return true;
            }
        }
        return false;
    }

    public void put(ReviewAllowEntry entry) {
        synchronized (mutex) {
            Map<String, ReviewAllowEntry> next = new HashMap<>(entries);
            next.put(key(entry.type(), entry.identityKey(), entry.ip()), entry);
            entries = Map.copyOf(next);
            version++;
        }
    }

    public void remove(ReviewType type, String identityKey, String ip) {
        synchronized (mutex) {
            String key = key(type, identityKey, ip);
            if (!entries.containsKey(key)) {
                return;
            }
            Map<String, ReviewAllowEntry> next = new HashMap<>(entries);
            next.remove(key);
            entries = Map.copyOf(next);
            version++;
        }
    }

    private static String key(ReviewType type, String identityKey, String ip) {
        return type.name() + '|' + identityKey + '|' + IpUtil.networkKey(ip);
    }
}
