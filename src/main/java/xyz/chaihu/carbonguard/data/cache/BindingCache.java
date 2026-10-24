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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import xyz.chaihu.carbonguard.data.model.Binding;

/**
 * Preloaded binding state. A pre-login handler must never wait for a JDBC query, so
 * it is allowed to consult only this cache.
 *
 * <p>The map reference is replaced atomically. A reader can therefore never observe a
 * half-cleared cache while the periodic refresh is running.</p>
 */
public final class BindingCache {
    private final AtomicBoolean ready = new AtomicBoolean();
    private final Object mutex = new Object();
    private volatile Map<String, Binding> byIdentity = Map.of();
    private long version;

    public long version() {
        synchronized (mutex) {
            return version;
        }
    }

    /**
     * Installs a refreshed snapshot only if no write-through update happened while the
     * database query was in flight. This prevents a slow refresh from overwriting a
     * just-completed binding.
     */
    public boolean replaceAllIfUnchanged(Map<String, Binding> bindings, long expectedVersion) {
        synchronized (mutex) {
            if (version != expectedVersion) {
                return false;
            }
            byIdentity = Map.copyOf(bindings);
            ready.set(true);
            return true;
        }
    }

    public boolean ready() {
        return ready.get();
    }

    public boolean isBound(String identityKey) {
        return byIdentity.containsKey(identityKey);
    }

    public Binding get(String identityKey) {
        return byIdentity.get(identityKey);
    }

    public void put(Binding binding) {
        synchronized (mutex) {
            Map<String, Binding> copy = new HashMap<>(byIdentity);
            copy.put(binding.identityKey(), binding);
            byIdentity = Map.copyOf(copy);
            version++;
        }
    }

    public void remove(String identityKey) {
        synchronized (mutex) {
            if (!byIdentity.containsKey(identityKey)) {
                return;
            }
            Map<String, Binding> copy = new HashMap<>(byIdentity);
            copy.remove(identityKey);
            byIdentity = Map.copyOf(copy);
            version++;
        }
    }

    public int size() {
        return byIdentity.size();
    }
}
