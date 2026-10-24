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
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

/**
 * Isolated Floodgate API surface. This class must only be loaded after a class-presence
 * check, so servers without Floodgate never see a NoClassDefFoundError.
 */
final class FloodgateBridge {
    private FloodgateBridge() {
    }

    static Lookup lookup(UUID uuid) {
        FloodgateApi api = FloodgateApi.getInstance();
        if (api == null || !api.isFloodgatePlayer(uuid)) {
            return Lookup.NOT_BEDROCK;
        }
        FloodgatePlayer player = api.getPlayer(uuid);
        if (player == null) {
            // Floodgate already identified this UUID as Bedrock, but the player record
            // is temporarily unavailable. Preserve the Bedrock fallback instead of
            // silently treating the account as a Java player.
            return new Lookup(true, "");
        }
        String xuid = player.getXuid();
        return new Lookup(true, xuid == null ? "" : xuid.trim());
    }

    record Lookup(boolean bedrock, String xuid) {
        static final Lookup NOT_BEDROCK = new Lookup(false, null);
    }
}
