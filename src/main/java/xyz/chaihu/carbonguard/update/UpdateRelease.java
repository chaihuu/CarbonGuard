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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;

/** Pure release parsing/version helpers, kept free of Bukkit for unit testing. */
public final class UpdateRelease {
    private UpdateRelease() {
    }

    /** Parses a GitHub release JSON object; returns null if it is not usable. */
    public static ReleaseInfo parseRelease(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();
            String tag = object.has("tag_name") ? object.get("tag_name").getAsString() : null;
            if (tag == null || tag.isBlank()) {
                return null;
            }
            String version = normalizeVersion(tag);
            String page = object.has("html_url") ? object.get("html_url").getAsString() : "";
            String assetName = null;
            String downloadUrl = null;
            if (object.has("assets") && object.get("assets").isJsonArray()) {
                JsonArray assets = object.getAsJsonArray("assets");
                for (JsonElement element : assets) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject asset = element.getAsJsonObject();
                    String name = asset.has("name") ? asset.get("name").getAsString() : "";
                    String url = asset.has("browser_download_url")
                            ? asset.get("browser_download_url").getAsString() : "";
                    String lowerName = name.toLowerCase(Locale.ROOT);
                    if (!lowerName.endsWith(".jar") || lowerName.contains("sources")
                            || lowerName.contains("javadoc")
                            || !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
                        continue;
                    }
                    if (assetName == null || lowerName.contains("carbonguard")) {
                        assetName = name;
                        downloadUrl = url;
                        if (lowerName.contains("carbonguard")) {
                            break;
                        }
                    }
                }
            }
            return new ReleaseInfo(version, page, assetName, downloadUrl);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String normalizeVersion(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    /** Numeric semver comparison; a stable release sorts above its pre-release. */
    public static int compareVersions(String left, String right) {
        String a = normalizeVersion(left);
        String b = normalizeVersion(right);
        String[] aParts = a.split("-", 2);
        String[] bParts = b.split("-", 2);
        int numeric = compareNumeric(aParts[0], bParts[0]);
        if (numeric != 0) {
            return numeric;
        }
        boolean aPre = aParts.length > 1;
        boolean bPre = bParts.length > 1;
        if (aPre != bPre) {
            return aPre ? -1 : 1;
        }
        if (!aPre) {
            return 0;
        }
        return aParts[1].compareToIgnoreCase(bParts[1]);
    }

    private static int compareNumeric(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? parsePart(a[i]) : 0;
            int bv = i < b.length ? parsePart(b[i]) : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int parsePart(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record ReleaseInfo(String version, String pageUrl, String assetName, String downloadUrl) {
    }
}
