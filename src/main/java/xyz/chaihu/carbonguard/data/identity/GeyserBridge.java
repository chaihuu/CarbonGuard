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

package xyz.chaihu.carbonguard.data.identity;

import java.util.UUID;
import org.geysermc.api.connection.Connection;
import org.geysermc.geyser.api.GeyserApi;

/**
 * Isolated Geyser API surface. Geyser is used only for Bedrock detection and for an
 * optional, authoritative observed XUID that can later seed a Floodgate migration.
 */
final class GeyserBridge {
    private GeyserBridge() {
    }

    static boolean isBedrock(UUID uuid) {
        try {
            GeyserApi api = GeyserApi.api();
            return api != null && api.isBedrockPlayer(uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String observedXuid(UUID uuid) {
        try {
            GeyserApi api = GeyserApi.api();
            if (api == null) {
                return null;
            }
            Connection connection = api.connectionByUuid(uuid);
            if (connection == null) {
                return null;
            }
            String xuid = connection.xuid();
            return xuid == null || xuid.isBlank() ? null : xuid.trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean online(UUID uuid) {
        try {
            GeyserApi api = GeyserApi.api();
            return api != null && api.connectionByUuid(uuid) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
