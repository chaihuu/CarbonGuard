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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.chaihu.carbonguard.util.DurationParser;
import xyz.chaihu.carbonguard.util.Tokens;

class TokensTest {
    @Test
    void hashesCodesWithSalt() {
        byte[] salt = Tokens.randomBytes(16);
        String one = Tokens.codeHash("123456", salt);
        String two = Tokens.codeHash("123456", salt);
        String other = Tokens.codeHash("123457", salt);
        assertEquals(one, two);
        assertNotEquals(one, other);
        assertTrue(Tokens.constantTimeEquals(one, two));
        assertFalse(Tokens.constantTimeEquals(one, other));
    }

    @Test
    void parsesDurations() {
        assertEquals(Duration.ofDays(7), DurationParser.parse("7d").orElseThrow());
        assertEquals(Duration.ofSeconds(45_000), DurationParser.parse("12h30m").orElseThrow());
        assertEquals(Duration.ZERO, DurationParser.parse("permanent").orElseThrow());
        assertTrue(DurationParser.parse("7x").isEmpty());
        assertTrue(DurationParser.parse("").isEmpty());
    }

    @Test
    void tokenEntropyAndShape() {
        String token = Tokens.random(32);
        assertTrue(token.length() >= 40);
        assertFalse(token.contains("="));
        assertEquals(Optional.of(6), Optional.of(Tokens.sixDigitCode().length()));
    }
}
