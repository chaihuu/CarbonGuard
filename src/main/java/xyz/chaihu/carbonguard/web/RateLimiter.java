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

package xyz.chaihu.carbonguard.web;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed one-minute window limiter keyed by client IP. */
public final class RateLimiter {
    private static final long WINDOW_MILLIS = 60_000L;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String key, int limit) {
        long now = System.currentTimeMillis();
        long windowStart = now - (now % WINDOW_MILLIS);
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.start != windowStart) {
                return new Window(windowStart, 1);
            }
            existing.count++;
            return existing;
        });
        if (windows.size() > 10_000) {
            cleanup(now);
        }
        return window.count <= limit;
    }

    public void cleanup() {
        cleanup(System.currentTimeMillis());
    }

    private void cleanup(long now) {
        long cutoff = now - WINDOW_MILLIS * 2;
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().start < cutoff) {
                iterator.remove();
            }
        }
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }
}
