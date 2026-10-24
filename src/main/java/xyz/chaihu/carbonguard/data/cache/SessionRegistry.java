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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks online sessions plus short-lived pre-login reservations.
 *
 * <p>The reservation is essential: several connections can complete
 * {@code AsyncPlayerPreLoginEvent} concurrently before the first one becomes online.
 * Counting only Bukkit's online players would let them all pass the per-IP limit.</p>
 */
public final class SessionRegistry {
    private static final long RESERVATION_MILLIS = 90_000L;
    private final Map<String, IpState> byIp = new ConcurrentHashMap<>();
    private final Map<UUID, Lease> byUuid = new ConcurrentHashMap<>();

    public synchronized AcquireResult tryAcquire(String ip, String identityKey, UUID uuid, int hardLimit) {
        long now = System.currentTimeMillis();
        Lease existing = byUuid.get(uuid);
        if (existing != null) {
            IpState oldState = byIp.get(existing.ip());
            if (oldState != null) {
                synchronized (oldState) {
                    Lease current = oldState.leases.get(existing.identityKey());
                    if (current != null && current.uuid().equals(uuid)) {
                        oldState.leases.remove(existing.identityKey());
                    }
                    if (oldState.leases.isEmpty()) {
                        byIp.remove(existing.ip(), oldState);
                    }
                }
            }
            byUuid.remove(uuid);
        }

        IpState state = byIp.computeIfAbsent(ip, ignored -> new IpState());
        synchronized (state) {
            state.leases.entrySet().removeIf(entry -> !entry.getValue().active
                    && entry.getValue().expiresAt <= now);
            long active = state.leases.values().stream()
                    .filter(lease -> lease.identityKey.equals(identityKey) || lease.active || lease.expiresAt > now)
                    .filter(lease -> !lease.identityKey.equals(identityKey))
                    .count();
            if (hardLimit > 0 && active >= hardLimit) {
                return new AcquireResult(false, (int) active);
            }
            Lease previousForIdentity = state.leases.get(identityKey);
            if (previousForIdentity != null && !previousForIdentity.uuid().equals(uuid)) {
                byUuid.remove(previousForIdentity.uuid(), previousForIdentity);
            }
            Lease lease = new Lease(identityKey, uuid, ip, now + RESERVATION_MILLIS, false);
            state.leases.put(identityKey, lease);
            byUuid.put(uuid, lease);
            return new AcquireResult(true, (int) active + 1);
        }
    }

    public void confirm(UUID uuid) {
        Lease lease = byUuid.get(uuid);
        if (lease == null) {
            return;
        }
        IpState state = byIp.get(lease.ip());
        if (state == null) {
            return;
        }
        synchronized (state) {
            Lease current = state.leases.get(lease.identityKey());
            if (current != null && current.uuid().equals(uuid)) {
                state.leases.put(lease.identityKey(),
                        new Lease(lease.identityKey(), uuid, lease.ip(), 0, true));
                byUuid.put(uuid, state.leases.get(lease.identityKey()));
            }
        }
    }

    public void release(UUID uuid) {
        Lease lease = byUuid.remove(uuid);
        if (lease == null) {
            return;
        }
        IpState state = byIp.get(lease.ip());
        if (state == null) {
            return;
        }
        synchronized (state) {
            Lease current = state.leases.get(lease.identityKey());
            if (current != null && current.uuid().equals(uuid)) {
                state.leases.remove(lease.identityKey());
            }
            if (state.leases.isEmpty()) {
                byIp.remove(lease.ip(), state);
            }
        }
    }

    public void releaseByIdentity(String identityKey) {
        for (Map.Entry<UUID, Lease> entry : byUuid.entrySet()) {
            if (entry.getValue().identityKey().equals(identityKey)) {
                release(entry.getKey());
                return;
            }
        }
    }

    public int onlineCount() {
        return (int) byUuid.values().stream().filter(Lease::active).count();
    }

    public int activeForIp(String ip) {
        IpState state = byIp.get(ip);
        if (state == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        synchronized (state) {
            return (int) state.leases.values().stream()
                    .filter(lease -> lease.active || lease.expiresAt > now)
                    .count();
        }
    }

    public Optional<String> ipOf(UUID uuid) {
        Lease lease = byUuid.get(uuid);
        return lease == null ? Optional.empty() : Optional.of(lease.ip());
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, IpState> entry : byIp.entrySet()) {
            IpState state = entry.getValue();
            synchronized (state) {
                state.leases.entrySet().removeIf(lease -> {
                    if (!lease.getValue().active && lease.getValue().expiresAt <= now) {
                        byUuid.remove(lease.getValue().uuid());
                        return true;
                    }
                    return false;
                });
                if (state.leases.isEmpty()) {
                    byIp.remove(entry.getKey(), state);
                }
            }
        }
    }

    public Map<String, Integer> snapshotByIp() {
        Map<String, Integer> result = new HashMap<>();
        byIp.forEach((ip, state) -> {
            int count = activeForIp(ip);
            if (count > 0) {
                result.put(ip, count);
            }
        });
        return result;
    }

    public record AcquireResult(boolean allowed, int activeCount) {
    }

    private record Lease(String identityKey, UUID uuid, String ip, long expiresAt, boolean active) {
    }

    private static final class IpState {
        private final Map<String, Lease> leases = new HashMap<>();
    }
}
