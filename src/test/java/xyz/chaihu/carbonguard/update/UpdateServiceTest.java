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

package xyz.chaihu.carbonguard.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateServiceTest {
    @Test
    void comparesSemanticVersionsIncludingPrerelease() {
        assertTrue(UpdateRelease.compareVersions("1.2.0", "1.1.9") > 0);
        assertTrue(UpdateRelease.compareVersions("v1.2.0", "1.2.0") == 0);
        assertTrue(UpdateRelease.compareVersions("1.2.0", "1.2.0-beta1") > 0);
        assertTrue(UpdateRelease.compareVersions("1.2.0-beta2", "1.2.0-beta1") > 0);
    }

    @Test
    void parsesGithubReleaseAndPrefersCarbonGuardJar() {
        String json = """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://github.com/chaihuu/CarbonGuard/releases/tag/v1.2.0",
                  "assets": [
                    {"name": "CarbonGuard-1.2.0-sources.jar",
                     "browser_download_url": "https://example.invalid/sources.jar"},
                    {"name": "CarbonGuard-1.2.0.jar",
                     "browser_download_url": "https://example.invalid/CarbonGuard-1.2.0.jar"}
                  ]
                }
                """;
        UpdateRelease.ReleaseInfo info = UpdateRelease.parseRelease(json);
        assertEquals("1.2.0", info.version());
        assertEquals("CarbonGuard-1.2.0.jar", info.assetName());
        assertEquals("https://example.invalid/CarbonGuard-1.2.0.jar", info.downloadUrl());
    }

    @Test
    void parsesReleaseWithoutJarAndRejectsGarbage() {
        UpdateRelease.ReleaseInfo info = UpdateRelease.parseRelease("""
                {"tag_name":"1.3.0","html_url":"https://example.invalid","assets":[]}
                """);
        assertEquals("1.3.0", info.version());
        assertNull(info.downloadUrl());
        assertNull(UpdateRelease.parseRelease("not json"));
    }
}
