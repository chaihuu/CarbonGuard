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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.BanRecord;
import xyz.chaihu.carbonguard.data.model.Binding;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.data.model.PlayerProfileView;
import xyz.chaihu.carbonguard.service.BanService;
import xyz.chaihu.carbonguard.service.PlayerService;
import xyz.chaihu.carbonguard.util.TimeUtil;

class PlayerDataTest {
    @TempDir
    Path tempDir;

    @Test
    void firstJoinIsRecordedOnceAndPlaytimeAccumulates() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("players.db"));
        PlayerIdentity identity = PlayerIdentity.xuid(UUID.randomUUID(), "Recorder", "123000111", null);
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-players-test"))) {
            data.init(false);
            long first = TimeUtil.now();
            data.recordJoin(identity, "203.0.113.140", first);
            flush(data);
            PlayerProfileView profile = data.database().webNow(connection ->
                    data.players().findExact(connection, identity.key())).orElseThrow();
            assertEquals(first, profile.firstJoin());
            assertEquals(1, profile.loginCount());

            Thread.sleep(5);
            data.recordJoin(identity, "203.0.113.140", TimeUtil.now());
            data.recordPlaytime(identity, 90_000L);
            flush(data);

            PlayerProfileView updated = data.database().webNow(connection ->
                    data.players().findExact(connection, identity.key())).orElseThrow();
            assertEquals(first, updated.firstJoin());
            assertEquals(2, updated.loginCount());
            assertEquals(90L, updated.playSeconds());
            assertTrue(updated.lastLogin() >= first);
        }
    }

    @Test
    void profileExposesBindingTimeAndSearchFindsByName() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("players2.db"));
        PlayerIdentity identity = PlayerIdentity.javaPlayer(UUID.randomUUID(), "BoundPlayer");
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-players-test"))) {
            data.init(false);
            data.recordJoin(identity, "203.0.113.140", TimeUtil.now());
            data.database().write(connection -> {
                data.identities().upsert(connection, identity, "203.0.113.150", TimeUtil.now());
                data.bindings().save(connection,
                        new Binding(identity.key(), "bound@qq.com", "1234567", 1_700_000_000_000L),
                        TimeUtil.now());
            }).get(5, TimeUnit.SECONDS);
            // The binding time shown to admins is the binding row, not the profile row.
            assertEquals(1_700_000_000_000L, data.database().webNow(connection ->
                    data.players().findExact(connection, identity.key())).orElseThrow().boundAt());

            PlayerProfileView profile = data.database().webNow(connection ->
                    data.players().findByPlayerName(connection, "boundplayer")).orElseThrow();
            assertTrue(profile.bound());
            assertEquals(1_700_000_000_000L, profile.boundAt());
            assertEquals("bound@qq.com", profile.email());
            assertEquals(1, data.database().webNow(connection ->
                    data.players().search(connection, "bound@qq.com", 10)).size());
        }
    }

    @Test
    void backfillCreatesProfilesForLegacyIdentities() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("players3.db"));
        PlayerIdentity identity = PlayerIdentity.javaPlayer(UUID.randomUUID(), "Legacy");
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-players-test"))) {
            data.init(false);
            data.database().write(connection ->
                    data.identities().upsert(connection, identity, "203.0.113.151", TimeUtil.now()))
                    .get(5, TimeUnit.SECONDS);
            int created = data.database().coreNow(connection ->
                    data.players().backfillFromIdentities(connection, TimeUtil.now()));
            assertEquals(1, created);
            assertTrue(data.database().coreNow(connection ->
                    data.players().findExact(connection, identity.key())).isPresent());
        }
    }

    @Test
    void unbindDoesNotTouchPlayerHistory() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("players4.db"));
        PlayerIdentity identity = PlayerIdentity.javaPlayer(UUID.randomUUID(), "KeepHistory");
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-players-test"))) {
            data.init(false);
            data.recordJoin(identity, "203.0.113.140", TimeUtil.now());
            BanService bans = new BanService(data, () -> config);
            BanRecord ban = bans.ban(identity, "t", Duration.ofHours(1), "tester", "203.0.113.152")
                    .get(5, TimeUnit.SECONDS);
            assertNotNull(ban);
            assertTrue(data.database().coreNow(connection ->
                    data.players().findExact(connection, identity.key())).isPresent());
        }
    }

    @Test
    void describeHandlesMissingProfile() {
        assertEquals("无记录", PlayerService.describe(null));
    }

    private static void flush(DataStore data) throws Exception {
        data.database().write(connection -> {
        }).get(5, TimeUnit.SECONDS);
    }
}
