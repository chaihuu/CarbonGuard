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
        return new CarbonGuardConfig(
                1,
                false,
                new CarbonGuardConfig.Storage(database, new CarbonGuardConfig.Pool(3, 3, 5_000, 60)),
                new CarbonGuardConfig.IpLimit(true, 3, 1, IpLimitMode.REJECT,
                        List.of("192.0.2.0/24"), List.of("xuid:999")),
                new CarbonGuardConfig.Ban(true, new CarbonGuardConfig.Ban.IdUidXuid(true),
                        new CarbonGuardConfig.Ban.IpAssociation(true, IpAssociationAction.PENDING_REVIEW, 168)),
                new CarbonGuardConfig.Web(true, "127.0.0.1", 8080, "http://127.0.0.1:8080",
                        "test-admin-key-with-enough-entropy-123", 120, 12, false),
                new CarbonGuardConfig.Smtp(true, "smtp.qq.com", 587, "bot@qq.com", "auth-code",
                        "bot@qq.com", false, 10_000),
                new CarbonGuardConfig.EmailVerify(true, true, 600, 1_800, 5, 60,
                        true, List.of("qq.com", "vip.qq.com", "foxmail.com")),
                new CarbonGuardConfig.Stats(true, 30),
                new CarbonGuardConfig.Review(168),
                new CarbonGuardConfig.Whitelist(true, List.of("127.0.0.1"), List.of())
        );
    }
}
