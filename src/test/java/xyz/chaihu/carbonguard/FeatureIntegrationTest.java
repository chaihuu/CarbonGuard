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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.Binding;
import xyz.chaihu.carbonguard.data.model.IdentitySource;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.data.model.ReviewRecord;
import xyz.chaihu.carbonguard.data.model.ReviewStatus;
import xyz.chaihu.carbonguard.data.model.ReviewType;
import xyz.chaihu.carbonguard.service.AdminService;
import xyz.chaihu.carbonguard.service.BindTicketService;
import xyz.chaihu.carbonguard.service.ReviewService;
import xyz.chaihu.carbonguard.util.TimeUtil;

class FeatureIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void approvedReviewSuppressesDuplicateAlerts() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("review.db"));
        PlayerIdentity identity = PlayerIdentity.xuid(UUID.randomUUID(), "Reviewer", "111222333", null);
        String ip = "203.0.113.70";
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-review-test"))) {
            data.init(false);
            data.recordLogin(identity, ip, "WARN_ONLY", "test");
            data.queueReview(ReviewType.IP_LIMIT, identity, ip, null, "test review");
            flush(data);

            ReviewRecord pending = data.database().webNow(connection ->
                    data.reviews().list(connection, ReviewStatus.PENDING, 10).get(0));
            ReviewService reviews = new ReviewService(data, () -> config);
            ReviewRecord approved = reviews.decide(pending.id(), ReviewStatus.APPROVED, "test")
                    .get(5, TimeUnit.SECONDS);

            assertEquals(ReviewStatus.APPROVED, approved.status());
            assertTrue(data.reviewCache().allowed(ReviewType.IP_LIMIT, identity, ip));

            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> reviews.decide(pending.id(), ReviewStatus.REJECTED, "test")
                            .get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void adminUnbindRemovesBindingAndPendingTickets() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("unbind.db"));
        PlayerIdentity identity = PlayerIdentity.javaPlayer(UUID.randomUUID(), "UnbindMe");
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-unbind-test"))) {
            data.init(false);
            data.database().write(connection -> {
                data.identities().upsert(connection, identity, "203.0.113.71", TimeUtil.now());
                data.bindings().save(connection,
                        new Binding(identity.key(), "unbind@qq.com", "1234567", TimeUtil.now()),
                        TimeUtil.now());
            }).get(5, TimeUnit.SECONDS);
            data.refreshCaches();
            assertTrue(data.bindingCache().isBound(identity.key()));

            BindTicketService tickets = new BindTicketService(data, () -> config);
            tickets.issue(identity, "203.0.113.71");
            AdminService admin = new AdminService(data, config, tickets);

            assertTrue(admin.unbind(identity.key(), "test").get(5, TimeUnit.SECONDS));
            assertFalse(data.bindingCache().isBound(identity.key()));
            assertEquals(0L, (long) data.database().webNow(connection -> data.bindings().count(connection)));
            data.database().write(connection ->
                    data.stats().purge(connection, TimeUtil.now(), 30)).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void identitySourceNeverDowngradesFromFallbackToJava() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("source.db"));
        UUID uuid = UUID.randomUUID();
        PlayerIdentity fallback = PlayerIdentity.fallback(uuid, "Bedrock", "444555666");
        PlayerIdentity javaIdentity = PlayerIdentity.javaPlayer(uuid, "Bedrock");
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-source-test"))) {
            data.init(false);
            data.database().write(connection -> {
                data.identities().upsert(connection, fallback, "203.0.113.72", TimeUtil.now());
                data.identities().upsert(connection, javaIdentity, "203.0.113.72", TimeUtil.now());
            }).get(5, TimeUnit.SECONDS);
            PlayerIdentity stored = data.database().coreNow(connection ->
                    data.identities().findByKey(connection, fallback.key()).orElseThrow());
            assertEquals(IdentitySource.UUID_FALLBACK, stored.source());
            assertEquals("444555666", stored.observedXuid());
        }
    }

    @Test
    void migrationIntoExistingXuidKeepsTargetBindingAndRemovesFallback() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("merge.db"));
        PlayerIdentity fallback = PlayerIdentity.fallback(UUID.randomUUID(), "MergeMe", "777888999");
        PlayerIdentity target = PlayerIdentity.xuid(UUID.randomUUID(), "MergeMe", "777888999", null);
        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-merge-test"))) {
            data.init(false);
            data.database().write(connection -> {
                data.identities().upsert(connection, fallback, "203.0.113.73", TimeUtil.now());
                data.identities().upsert(connection, target, "203.0.113.74", TimeUtil.now());
                data.bindings().save(connection,
                        new Binding(fallback.key(), "fallback@qq.com", "111111", TimeUtil.now()),
                        TimeUtil.now());
                data.bindings().save(connection,
                        new Binding(target.key(), "target@qq.com", "222222", TimeUtil.now()),
                        TimeUtil.now());
            }).get(5, TimeUnit.SECONDS);
        }

        try (DataStore data = new DataStore(config, Logger.getLogger("carbonguard-merge-test"))) {
            data.init(true);
            assertTrue(data.database().coreNow(connection ->
                    data.identities().findByKey(connection, fallback.key())).isEmpty());
            assertTrue(data.bindingCache().isBound(target.key()));
            assertEquals("target@qq.com", data.bindingCache().get(target.key()).email());
        }
    }

    private static void flush(DataStore data) throws Exception {
        data.database().write(connection -> {
        }).get(5, TimeUnit.SECONDS);
    }
}
