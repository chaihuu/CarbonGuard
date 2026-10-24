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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.chaihu.carbonguard.util.CidrMatcher;
import xyz.chaihu.carbonguard.util.IpUtil;

class CidrMatcherTest {
    @Test
    void matchesIpv4Cidr() {
        CidrMatcher matcher = CidrMatcher.of(List.of("192.0.2.0/24", "10.0.0.7"));
        assertTrue(matcher.matches("192.0.2.55"));
        assertTrue(matcher.matches("10.0.0.7"));
        assertFalse(matcher.matches("10.0.0.8"));
        assertFalse(matcher.matches("192.0.3.1"));
    }

    @Test
    void matchesIpv6AndDoesNotMixFamilies() {
        CidrMatcher matcher = CidrMatcher.of(List.of("2001:db8::/32"));
        assertTrue(matcher.matches("2001:db8:1:2::1"));
        assertFalse(matcher.matches("192.0.2.1"));
    }

    @Test
    void normalizesMappedIpv4() throws Exception {
        assertTrue("192.0.2.9".equals(IpUtil.normalize("::ffff:192.0.2.9")));
    }

    @Test
    void rejectsInvalidEntries() {
        assertTrue(CidrMatcher.of(List.of("999.1.1.1", "192.0.2.0/99", "nonsense")).isEmpty());
    }
}
