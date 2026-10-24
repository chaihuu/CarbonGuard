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

import org.junit.jupiter.api.Test;
import xyz.chaihu.carbonguard.util.IpUtil;

class IpUtilTest {
    @Test
    void rejectsPortsAndMalformedColonStrings() {
        assertFalse(IpUtil.isLiteral(":foo"));
        assertFalse(IpUtil.isLiteral("1.2.3.4:25565"));
        assertFalse(IpUtil.isLiteral("example.com"));
        assertTrue(IpUtil.isLiteral("2001:db8::1"));
        assertTrue(IpUtil.isLiteral("203.0.113.7"));
    }

    @Test
    void ipv6NetworkKeyFoldsTo64Bits() {
        assertEquals("203.0.113.7", IpUtil.networkKey("203.0.113.7"));
        assertEquals(IpUtil.networkKey("2001:db8:1:2::1"), IpUtil.networkKey("2001:db8:1:2::ffff"));
        String key = IpUtil.networkKey("2001:db8:1:2::1");
        assertTrue(key.endsWith("/64"));
        assertFalse(IpUtil.networkKey("2001:db8:1:3::1").equals(key));
    }

    @Test
    void mappedIpv4IsFolded() {
        assertEquals("192.0.2.9", IpUtil.normalize("::ffff:192.0.2.9"));
    }
}
