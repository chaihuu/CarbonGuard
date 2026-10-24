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

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses compact ban durations such as {@code 7d}, {@code 12h30m}, or {@code permanent}. */
public final class DurationParser {
    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])");
    private static final Pattern DURATION = Pattern.compile("(\\d+[smhdw])+");

    private DurationParser() {
    }

    public static boolean isPermanentToken(String input) {
        if (input == null) {
            return false;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        return value.equals("perm") || value.equals("permanent") || value.equals("forever")
                || value.equals("永久");
    }

    public static boolean looksLikeDuration(String input) {
        if (input == null) {
            return false;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        return !value.isEmpty() && DURATION.matcher(value).matches();
    }

    public static Optional<Duration> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.equals("perm") || value.equals("permanent") || value.equals("forever") || value.equals("永久")) {
            return Optional.of(Duration.ZERO);
        }        Matcher matcher = PART.matcher(value);
        long seconds = 0;
        int matched = 0;
        while (matcher.find()) {
            try {
                long amount = Long.parseLong(matcher.group(1));
                seconds = Math.addExact(seconds, switch (matcher.group(2)) {
                    case "s" -> amount;
                    case "m" -> Math.multiplyExact(amount, 60);
                    case "h" -> Math.multiplyExact(amount, 3_600);
                    case "d" -> Math.multiplyExact(amount, 86_400);
                    case "w" -> Math.multiplyExact(amount, 604_800);
                    default -> 0;
                });
            } catch (ArithmeticException | NumberFormatException e) {
                return Optional.empty();
            }
            matched += matcher.group().length();
        }
        if (matched != value.length() || seconds < 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(seconds));
    }

    public static String human(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return "永久";
        }
        long seconds = duration.toSeconds();
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("天");
        }
        if (hours > 0) {
            out.append(hours).append("小时");
        }
        if (minutes > 0 && days == 0) {
            out.append(minutes).append("分钟");
        }
        if (out.isEmpty()) {
            out.append(Math.max(1, seconds)).append("秒");
        }
        return out.toString();
    }
}
