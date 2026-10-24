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
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;import xyz.chaihu.carbonguard.util.IpUtil;

/**
 * Tracks online sessions plus short-lived pre-login reservations.
 *
 * <p>Leases are keyed by a per-login session id, not only by UUID. A failed or
 * duplicate connection can therefore release its own reservation without deleting
 * the already-confirmed session of the same player. IP accounting uses
 * {@link IpUtil#networkKey(String)} so IPv6 privacy addresses in one /64 cannot
 * multiply the per-IP account count.</p>
 */
public final class SessionRegistry {
    private static final long RESERVATION_MILLIS = 90_000L;
    private final Object lock = new Object();
    private final Map<String, IpState> byIp = new HashMap<>();
    private final Map<String, Lease> bySession = new HashMap<>();
    private final Map<UUID, Set<String>> byUuid = new HashMap<>();

    public AcquireResult tryAcquire(String ip, String identityKey, UUID uuid, int hardLimit) {
        String networkKey = IpUtil.networkKey(ip);
        String exactIp = IpUtil.normalize(ip);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            cleanupLocked(now);
            IpState state = byIp.computeIfAbsent(networkKey, ignored -> new IpState());
            long active = state.sessions.values().stream()
                    .filter(lease -> !lease.identityKey().equals(identityKey))
                    .map(Lease::identityKey)
                    .distinct()
                    .count();
            if (hardLimit > 0 && active >= hardLimit) {
                return new AcquireResult(false, (int) active, null);
            }
            String sessionId = UUID.randomUUID().toString();
            Lease lease = new Lease(sessionId, identityKey, uuid, exactIp, networkKey,
                    now + RESERVATION_MILLIS, false);
            state.sessions.put(sessionId, lease);
            bySession.put(sessionId, lease);
            byUuid.computeIfAbsent(uuid, ignored -> new HashSet<>()).add(sessionId);
            return new AcquireResult(true, (int) active + 1, sessionId);
        }
    }

    /**
     * Confirms a specific pre-login reservation. Returns false if the reservation
     * expired or was already released; callers must then re-evaluate the login.
     */
    public boolean confirm(UUID uuid, String sessionId) {
        if (uuid == null || sessionId == null) {
            return false;
        }
        synchronized (lock) {
            Lease lease = bySession.get(sessionId);
            if (lease == null || !lease.uuid().equals(uuid)) {
                return false;
            }
            if (!lease.active()) {
                Lease confirmed = new Lease(lease.sessionId(), lease.identityKey(), lease.uuid(),
                        lease.ip(), lease.networkKey(), 0L, true);
                bySession.put(sessionId, confirmed);
                IpState state = byIp.get(lease.networkKey());
                if (state != null) {
                    state.sessions.put(sessionId, confirmed);
                }
            }
            return true;
        }
    }

    /** Compatibility helper: confirms the first live reservation for this UUID. */
    public void confirm(UUID uuid) {
        synchronized (lock) {
            for (String sessionId : sessionIds(uuid)) {
                Lease lease = bySession.get(sessionId);
                if (lease != null && !lease.active()) {
                    confirm(uuid, sessionId);
                    return;
                }
            }
        }
    }

    /**
     * Confirms a session, creating an active lease if the reservation was evicted.
     * Used on join so a slow login cannot silently disappear from the online count.
     */
    public String confirmOrCreate(String ip, String identityKey, UUID uuid, String sessionId) {
        synchronized (lock) {
            if (sessionId != null && confirm(uuid, sessionId)) {
                return sessionId;
            }
            long now = System.currentTimeMillis();
            String networkKey = IpUtil.networkKey(ip);
            String exactIp = IpUtil.normalize(ip);
            String id = sessionId == null ? UUID.randomUUID().toString() : sessionId;
            Lease lease = new Lease(id, identityKey, uuid, exactIp, networkKey, 0L, true);
            bySession.put(id, lease);
            byIp.computeIfAbsent(networkKey, ignored -> new IpState()).sessions.put(id, lease);
            byUuid.computeIfAbsent(uuid, ignored -> new HashSet<>()).add(id);
            return id;
        }
    }

    public void release(UUID uuid, String sessionId) {
        if (uuid == null || sessionId == null) {
            return;
        }
        synchronized (lock) {
            removeLocked(sessionId);
        }
    }

    /** Releases every session for the UUID (compatibility/test helper). */
    public void release(UUID uuid) {
        if (uuid == null) {
            return;
        }
        synchronized (lock) {
            for (String sessionId : sessionIds(uuid)) {
                removeLocked(sessionId);
            }
        }
    }

    /** Releases only confirmed sessions, leaving a concurrent pre-login reservation intact. */
    public void releaseActive(UUID uuid) {
        if (uuid == null) {
            return;
        }
        synchronized (lock) {
            for (String sessionId : sessionIds(uuid)) {
                Lease lease = bySession.get(sessionId);
                if (lease != null && lease.active()) {
                    removeLocked(sessionId);
                }
            }
        }
    }

    public void releaseByIdentity(String identityKey) {
        if (identityKey == null) {
            return;
        }
        synchronized (lock) {
            for (String sessionId : new HashSet<>(bySession.keySet())) {
                Lease lease = bySession.get(sessionId);
                if (lease != null && lease.identityKey().equals(identityKey)) {
                    removeLocked(sessionId);
                }
            }
        }
    }

    public int onlineCount() {
        synchronized (lock) {
            return (int) bySession.values().stream()
                    .filter(Lease::active)
                    .map(Lease::identityKey)
                    .distinct()
                    .count();
        }
    }

    public int activeForIp(String ip) {
        String networkKey = IpUtil.networkKey(ip);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            IpState state = byIp.get(networkKey);
            if (state == null) {
                return 0;
            }
            return (int) state.sessions.values().stream()
                    .filter(lease -> lease.active() || lease.expiresAt() > now)
                    .map(Lease::identityKey)
                    .distinct()
                    .count();
        }
    }

    public Optional<String> ipOf(UUID uuid) {
        synchronized (lock) {
            return sessionIds(uuid).stream()
                    .map(bySession::get)
                    .filter(lease -> lease != null)
                    .max((a, b) -> Boolean.compare(a.active(), b.active()))
                    .map(Lease::ip);
        }
    }

    public void cleanup() {
        synchronized (lock) {
            cleanupLocked(System.currentTimeMillis());
        }
    }

    public Map<String, Integer> snapshotByIp() {
        Map<String, Integer> result = new HashMap<>();
        synchronized (lock) {
            long now = System.currentTimeMillis();
            byIp.forEach((key, state) -> {
                int count = (int) state.sessions.values().stream()
                        .filter(lease -> lease.active() || lease.expiresAt() > now)
                        .map(Lease::identityKey)
                        .distinct()
                        .count();
                if (count > 0) {
                    result.put(key, count);
                }
            });
        }
        return result;
    }

    private Set<String> sessionIds(UUID uuid) {
        Set<String> ids = byUuid.get(uuid);
        return ids == null ? Set.of() : new HashSet<>(ids);
    }

    private void cleanupLocked(long now) {
        java.util.List<String> expired = new java.util.ArrayList<>();
        for (Lease lease : bySession.values()) {
            if (!lease.active() && lease.expiresAt() <= now) {
                expired.add(lease.sessionId());
            }
        }
        for (String sessionId : expired) {
            removeLocked(sessionId);
        }
        byIp.entrySet().removeIf(entry -> entry.getValue().sessions.isEmpty());
    }

    private void removeLocked(String sessionId) {
        Lease lease = bySession.remove(sessionId);
        if (lease == null) {
            return;
        }
        IpState state = byIp.get(lease.networkKey());
        if (state != null) {
            state.sessions.remove(sessionId);
            if (state.sessions.isEmpty()) {
                byIp.remove(lease.networkKey(), state);
            }
        }
        Set<String> ids = byUuid.get(lease.uuid());
        if (ids != null) {
            ids.remove(sessionId);
            if (ids.isEmpty()) {
                byUuid.remove(lease.uuid(), ids);
            }
        }
    }

    public record AcquireResult(boolean allowed, int activeCount, String sessionId) {
    }

    private record Lease(String sessionId, String identityKey, UUID uuid, String ip,
                         String networkKey, long expiresAt, boolean active) {
    }

    private static final class IpState {
        private final Map<String, Lease> sessions = new HashMap<>();
    }
}
