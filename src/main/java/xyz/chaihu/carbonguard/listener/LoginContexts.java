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

package xyz.chaihu.carbonguard.listener;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;

/** Per-login context shared by the async pre-login and synchronous join listeners. */
public final class LoginContexts {
    private final ConcurrentHashMap<UUID, Context> contexts = new ConcurrentHashMap<>();

    public void put(UUID uuid, Context context) {
        contexts.put(uuid, context);
    }

    public Optional<Context> get(UUID uuid) {
        return Optional.ofNullable(contexts.get(uuid));
    }

    public void remove(UUID uuid) {
        contexts.remove(uuid);
    }

    public record Context(PlayerIdentity identity, String ip) {
    }
}
