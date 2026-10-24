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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.chaihu.carbonguard.data.cache.BanCache;
import xyz.chaihu.carbonguard.data.cache.BindingCache;
import xyz.chaihu.carbonguard.data.model.AssociationHit;
import xyz.chaihu.carbonguard.data.model.BanRecord;
import xyz.chaihu.carbonguard.data.model.Binding;
import xyz.chaihu.carbonguard.util.TimeUtil;

class CacheSnapshotTest {
    @Test
    void staleBindingRefreshCannotOverwriteNewBinding() {
        BindingCache cache = new BindingCache();
        assertTrue(cache.replaceAllIfUnchanged(Map.of(), 0));
        long generation = cache.version();
        cache.put(new Binding("uuid:one", "one@qq.com", "10001", TimeUtil.now()));
        assertFalse(cache.replaceAllIfUnchanged(Map.of(), generation));
        assertTrue(cache.isBound("uuid:one"));
    }

    @Test
    void staleBanRefreshCannotOverwriteNewBan() {
        BanCache cache = new BanCache();
        assertTrue(cache.replaceAllIfUnchanged(List.of(), List.of(), 0));
        long generation = cache.version();
        long now = TimeUtil.now();
        BanRecord ban = new BanRecord(10, "uuid:" + UUID.randomUUID(), "Tester", "reason", "tester",
                now, 0, true, 0, null);
        AssociationHit hit = new AssociationHit(1, 10, "203.0.113.30", ban.identityKey(),
                "reason", "Tester", now, 0, 0);
        cache.addBan(ban, List.of(hit));
        assertFalse(cache.replaceAllIfUnchanged(List.of(), List.of(), generation));
        assertTrue(cache.directBan(ban.identityKey()).isPresent());
        assertTrue(cache.association("203.0.113.30").isPresent());
    }
}
