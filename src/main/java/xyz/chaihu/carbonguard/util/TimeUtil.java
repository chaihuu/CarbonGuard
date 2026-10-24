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

package xyz.chaihu.carbonguard.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Epoch-millisecond helpers; the database always stores UTC epoch millis. */
public final class TimeUtil {
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private TimeUtil() {
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    public static long plusSeconds(long seconds) {
        return now() + seconds * 1_000L;
    }

    public static String display(long epochMillis) {
        if (epochMillis <= 0) {
            return "永久";
        }
        return DISPLAY.format(Instant.ofEpochMilli(epochMillis));
    }

    public static boolean expired(long expiresAt) {
        return expiresAt > 0 && expiresAt <= now();
    }
}
