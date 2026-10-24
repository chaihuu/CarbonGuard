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

package xyz.chaihu.carbonguard.service;

import java.util.List;
import net.kyori.adventure.text.Component;
import xyz.chaihu.carbonguard.data.model.ReviewType;

public record LoginDecision(
        boolean allowed,
        Component message,
        String outcome,
        String detail,
        List<ReviewAlert> alerts,
        boolean banStyle
) {
    public static LoginDecision allow(String outcome, String detail, List<ReviewAlert> alerts) {
        return new LoginDecision(true, null, outcome, detail, List.copyOf(alerts), false);
    }

    public static LoginDecision deny(Component message, String detail, boolean banStyle) {
        return new LoginDecision(false, message, "DENIED", detail, List.of(), banStyle);
    }

    public record ReviewAlert(ReviewType type, Long banId, String reason) {
    }
}
