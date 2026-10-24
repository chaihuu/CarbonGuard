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

import java.nio.file.Path;
import java.util.List;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.config.IpAssociationAction;
import xyz.chaihu.carbonguard.config.IpLimitMode;

public final class TestConfig {
    private TestConfig() {
    }

    public static CarbonGuardConfig create(Path database) {
        return create(database, true);
    }

    public static CarbonGuardConfig create(Path database, boolean requireBinding) {
        CarbonGuardConfig base = createWithBinding(database, requireBinding,
                new CarbonGuardConfig.Binding(true, "/bind", true));
        return base;
    }

    /** Variant with an explicit binding configuration, used by the /bind flow tests. */
    public static CarbonGuardConfig createWithBinding(Path database, boolean requireBinding,
                                                      CarbonGuardConfig.Binding binding) {
        return new CarbonGuardConfig(
                1,
                false,
                new CarbonGuardConfig.Storage(database, new CarbonGuardConfig.Pool(3, 3, 5_000, 60)),
                ipLimit(true, List.of("192.0.2.0/24"), List.of("xuid:999")),
                new CarbonGuardConfig.Ban(true, new CarbonGuardConfig.Ban.IdUidXuid(true),
                        new CarbonGuardConfig.Ban.IpAssociation(true, IpAssociationAction.PENDING_REVIEW, 168)),
                new CarbonGuardConfig.Web(true, "127.0.0.1", 9981, "https://cg.example.com",
                        "test-admin-key-with-enough-entropy-123", 120, 12, false),
                binding,
                new CarbonGuardConfig.Smtp(true, "smtp.qq.com", 587, "bot@qq.com", "auth-code",
                        "bot@qq.com", false, 10_000),
                new CarbonGuardConfig.EmailVerify(true, requireBinding, 600, 5, 60,
                        true, List.of("qq.com", "vip.qq.com", "foxmail.com")),
                new CarbonGuardConfig.Stats(true, 30),
                new CarbonGuardConfig.Review(168),
                new CarbonGuardConfig.BStats(false, 0),
                new CarbonGuardConfig.UpdateCheck(false,
                        "https://api.github.com/repos/chaihuu/CarbonGuard/releases/latest",
                        360, false, 20),
                new CarbonGuardConfig.Players(true, 0),
                new CarbonGuardConfig.Whitelist(true, List.of("127.0.0.1"), List.of())
        );
    }

    public static CarbonGuardConfig createWithBedrockExemption(Path database) {
        CarbonGuardConfig base = create(database, false);
        return new CarbonGuardConfig(base.configVersion(), base.debug(), base.storage(),
                ipLimit(false, List.of(), List.of()), base.ban(), base.web(), base.binding(),
                base.smtp(), base.emailVerify(), base.stats(), base.review(), base.bstats(),
                base.updateCheck(), base.players(), base.whitelist());
    }

    private static CarbonGuardConfig.IpLimit ipLimit(boolean exemptBedrock, List<String> ips,
                                                     List<String> players) {
        return new CarbonGuardConfig.IpLimit(true, 3, 1, IpLimitMode.REJECT, exemptBedrock,
                ips, players);
    }}
