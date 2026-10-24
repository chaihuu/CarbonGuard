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

package xyz.chaihu.carbonguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.chaihu.carbonguard.data.cache.SessionRegistry;

class SessionRegistryTest {
    @Test
    void reservationsCountTowardsTheLimitAndReconnectIsAllowed() {
        SessionRegistry registry = new SessionRegistry();
        String ip = "203.0.113.20";
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertTrue(registry.tryAcquire(ip, "uuid:" + first, first, 2).allowed());
        assertTrue(registry.tryAcquire(ip, "uuid:" + second, second, 2).allowed());
        assertEquals(2, registry.activeForIp(ip));

        assertFalse(registry.tryAcquire(ip, "uuid:" + third, third, 2).allowed());
        assertTrue(registry.tryAcquire(ip, "uuid:" + first, first, 2).allowed());

        registry.confirm(first);
        assertEquals(1, registry.onlineCount());
        registry.confirm(second);
        assertEquals(2, registry.onlineCount());
        registry.release(first);
        assertEquals(1, registry.onlineCount());
        assertTrue(registry.tryAcquire(ip, "uuid:" + third, third, 2).allowed());
    }

    @Test
    void replacingAnIdentityRemovesItsOldConnectionReservation() {
        SessionRegistry registry = new SessionRegistry();
        String ip = "203.0.113.21";
        UUID oldUuid = UUID.randomUUID();
        UUID newUuid = UUID.randomUUID();
        String identity = "uuid:" + oldUuid;

        assertTrue(registry.tryAcquire(ip, identity, oldUuid, 0).allowed());
        assertTrue(registry.tryAcquire(ip, identity, newUuid, 0).allowed());
        registry.release(oldUuid);
        assertEquals(1, registry.activeForIp(ip));
        registry.confirm(newUuid);
        assertEquals(1, registry.onlineCount());
        registry.release(newUuid);
        assertEquals(0, registry.activeForIp(ip));
    }

    @Test
    void zeroMeansUnlimited() {
        SessionRegistry registry = new SessionRegistry();
        String ip = "203.0.113.22";
        for (int i = 0; i < 100; i++) {
            UUID uuid = UUID.randomUUID();
            assertTrue(registry.tryAcquire(ip, "uuid:" + uuid, uuid, 0).allowed());
        }
        assertEquals(100, registry.activeForIp(ip));
    }

    @Test
    void rejectedLoginAtAnotherIpKeepsTheConfirmedSession() {
        SessionRegistry registry = new SessionRegistry();
        UUID player = UUID.randomUUID();
        SessionRegistry.AcquireResult first = registry.tryAcquire("10.0.0.1",
                "uuid:" + player, player, 0);
        assertTrue(registry.confirm(player, first.sessionId()));

        for (int i = 0; i < 3; i++) {
            UUID other = UUID.randomUUID();
            SessionRegistry.AcquireResult acquired = registry.tryAcquire("10.0.0.2",
                    "uuid:" + other, other, 0);
            assertTrue(registry.confirm(other, acquired.sessionId()));
        }

        SessionRegistry.AcquireResult rejected = registry.tryAcquire("10.0.0.2",
                "uuid:" + player, player, 3);
        assertFalse(rejected.allowed());
        assertEquals(1, registry.activeForIp("10.0.0.1"));
        assertEquals(4, registry.onlineCount());
    }

    @Test
    void sessionSpecificReleaseDoesNotDeleteNewerConnection() {
        SessionRegistry registry = new SessionRegistry();
        UUID player = UUID.randomUUID();
        String identity = "uuid:" + player;
        SessionRegistry.AcquireResult old = registry.tryAcquire("10.0.0.3", identity, player, 0);
        SessionRegistry.AcquireResult newest = registry.tryAcquire("10.0.0.3", identity, player, 0);
        assertTrue(registry.confirm(player, newest.sessionId()));

        registry.release(player, old.sessionId());
        assertEquals(1, registry.activeForIp("10.0.0.3"));
        assertTrue(registry.confirm(player, newest.sessionId()));
        registry.release(player, newest.sessionId());
        assertEquals(0, registry.activeForIp("10.0.0.3"));
    }

    @Test
    void ipv6PrivacyAddressesInSamePrefixShareTheLimit() {
        SessionRegistry registry = new SessionRegistry();
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        assertTrue(registry.tryAcquire("2001:db8:1:2::1", "uuid:" + one, one, 1).allowed());
        assertFalse(registry.tryAcquire("2001:db8:1:2::2", "uuid:" + two, two, 1).allowed());
    }
}
