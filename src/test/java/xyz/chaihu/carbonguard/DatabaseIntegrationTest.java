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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import xyz.chaihu.carbonguard.data.model.ReviewStatus;
import xyz.chaihu.carbonguard.data.model.ReviewType;
import xyz.chaihu.carbonguard.service.BanService;
import xyz.chaihu.carbonguard.util.TimeUtil;

class DatabaseIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void schemaCachesBansBindingsAndReviewsWork() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("carbonguard.db"));
        PlayerIdentity identity = PlayerIdentity.xuid(UUID.randomUUID(), "BedrockTester", "123456789", null);
        long now = TimeUtil.now();

        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-test"))) {
            data.init(false);
            assertTrue(data.bindingCache().ready());

            long foreignKeys = data.database().coreNow(connection -> {
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("PRAGMA foreign_keys")) {
                    return result.next() ? result.getLong(1) : 0;
                }
            });
            assertEquals(1, foreignKeys);

            data.recordLogin(identity, "203.0.113.9", "ALLOWED", "test");
            flush(data);
            assertTrue(data.database().coreNow(connection ->
                    data.identities().findByKey(connection, identity.key())).isPresent());

            data.database().write(connection ->
                    data.bindings().save(connection,
                            new Binding(identity.key(), "tester@qq.com", "12345678", now), now))
                    .get(5, TimeUnit.SECONDS);
            data.refreshCaches();
            assertTrue(data.bindingCache().isBound(identity.key()));
            assertEquals("tester@qq.com", data.bindingCache().get(identity.key()).email());

            data.queueReview(ReviewType.IP_LIMIT, identity, "203.0.113.9", null, "test review");
            flush(data);
            assertEquals(1, data.database().webNow(connection ->
                    data.reviews().list(connection, ReviewStatus.PENDING, 10)).size());

            BanService bans = new BanService(data, () -> config);
            BanRecord ban = bans.ban(identity, "integration test", Duration.ofHours(2), "tester", "203.0.113.9")
                    .get(5, TimeUnit.SECONDS);
            assertNotNull(ban);
            assertTrue(data.banCache().directBan(identity.key()).isPresent());
            assertTrue(data.banCache().association("203.0.113.9").isPresent());

            data.refreshCaches();
            assertTrue(data.banCache().directBan(identity.key()).isPresent());
            assertTrue(data.banCache().association("203.0.113.9").isPresent());

            assertTrue(bans.unban(ban.id(), "tester").get(5, TimeUnit.SECONDS));
            assertFalse(data.banCache().directBan(identity.key()).isPresent());
            assertFalse(data.banCache().association("203.0.113.9").isPresent());
            assertEquals(0L, (long) data.database().webNow(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COUNT(*) FROM ban_ip_assoc WHERE ban_id=?")) {
                    ps.setLong(1, ban.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : -1L;
                    }
                }
            }));
        }
    }

    @Test
    void uniqueEmailIsEnforcedAndTransactionsRollBack() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("unique.db"));
        PlayerIdentity one = PlayerIdentity.javaPlayer(UUID.randomUUID(), "One");
        PlayerIdentity two = PlayerIdentity.javaPlayer(UUID.randomUUID(), "Two");
        long now = TimeUtil.now();
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-test"))) {
            data.init(false);
            data.database().write(connection -> {
                data.identities().upsert(connection, one, "198.51.100.1", now);
                data.identities().upsert(connection, two, "198.51.100.2", now);
                data.bindings().save(connection, new Binding(one.key(), "same@qq.com", "10001", now), now);
            }).get(5, TimeUnit.SECONDS);

            boolean failed = false;
            try {
                data.database().write(connection ->
                        data.bindings().save(connection,
                                new Binding(two.key(), "SAME@qq.com", "10002", now), now))
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                failed = true;
            }
            assertTrue(failed);
            assertEquals(1L, (long) data.database().webNow(connection -> data.bindings().count(connection)));

            data.database().write(connection -> {
                data.identities().upsert(connection, one, "198.51.100.9", now);
                throw new IllegalStateException("rollback");
            }).handle((ignored, error) -> null).get(5, TimeUnit.SECONDS);

            String lastIp = data.database().coreNow(connection ->
                    data.identities().findByKey(connection, one.key()).orElseThrow().displayIdentity());
            assertTrue(lastIp.contains("UUID"));
        }
    }

    @Test
    void fallbackIdentityMigratesToObservedXuidWhenFloodgateAppears() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("migrate.db"));
        PlayerIdentity fallback = PlayerIdentity.fallback(UUID.randomUUID(), "BedrockFallback", "777888999");
        long now = TimeUtil.now();
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-test"))) {
            data.init(false);
            data.recordLogin(fallback, "198.51.100.44", "ALLOWED", "fallback");
            flush(data);
            data.database().write(connection -> data.bindings().save(connection,
                    new Binding(fallback.key(), "fallback@qq.com", "777888", now), now)).get(5, TimeUnit.SECONDS);
            data.refreshCaches();
        }

        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-test"))) {
            data.init(true);
            assertTrue(data.database().coreNow(connection ->
                    data.identities().findByKey(connection, "xuid:777888999")).isPresent());
            assertTrue(data.database().coreNow(connection ->
                    data.identities().findByKey(connection, fallback.key())).isEmpty());
            assertTrue(data.bindingCache().isBound("xuid:777888999"));
            assertEquals("fallback@qq.com", data.bindingCache().get("xuid:777888999").email());
        }
    }

    private static void flush(DataStore data) throws Exception {
        data.database().write(connection -> {
        }).get(5, TimeUnit.SECONDS);
    }
}
