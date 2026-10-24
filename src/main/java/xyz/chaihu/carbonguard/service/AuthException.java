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

public final class AuthException extends RuntimeException {
    private final AuthError error;

    public AuthException(AuthError error, String message) {
        super(message);
        this.error = error;
    }

    public AuthError error() {
        return error;
    }

    public enum AuthError {
        INVALID_REQUEST(400),
        INVALID_TICKET(400),
        ALREADY_BOUND(409),
        INVALID_EMAIL(400),
        EMAIL_NOT_ALLOWED(400),
        EMAIL_TAKEN(409),
        COOLDOWN(429),
        SMTP_UNAVAILABLE(503),
        SMTP_FAILED(502),
        INVALID_CODE(400),
        CODE_EXPIRED(410),
        TOO_MANY_ATTEMPTS(429),
        INVALID_QQ(400),
        INTERNAL(500);

        private final int httpStatus;

        AuthError(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }
}
