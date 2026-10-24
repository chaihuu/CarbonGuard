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

package xyz.chaihu.carbonguard.data.model;

/**
 * A verified binding. {@code email} is the display address (for example
 * {@code 123456@qq.com}) and {@code emailKey} is the normalised ID used by the
 * verification flow, so both can be checked without a second lookup.
 */
public record Binding(String identityKey, String email, String qq, String emailKey, long verifiedAt) {
    public Binding(String identityKey, String email, String qq, long verifiedAt) {
        this(identityKey, email, qq, email, verifiedAt);
    }

    public static String emailKeyOf(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
