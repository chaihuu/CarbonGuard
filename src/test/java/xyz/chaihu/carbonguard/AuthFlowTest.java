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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.function.Executable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.mail.MailSender;
import xyz.chaihu.carbonguard.service.AuthException;
import xyz.chaihu.carbonguard.service.AuthService;
import xyz.chaihu.carbonguard.service.AuthService.ChallengeIssued;
import xyz.chaihu.carbonguard.service.BindTicketService;

class AuthFlowTest {
    @TempDir
    Path tempDir;

    @Test
    void ticketCodeAndChallengeAreSingleUse() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("auth.db"));
        try (DataStore data = new DataStore(config, Logger.getLogger("CarbonGuard-auth-test"))) {
            data.init(false);
            BindTicketService tickets = new BindTicketService(data, () -> config);
            FakeMail mail = new FakeMail();
            AuthService auth = new AuthService(data, tickets, mail, () -> config,
                    Logger.getLogger("CarbonGuard-auth-test"));
            PlayerIdentity identity = PlayerIdentity.xuid(UUID.randomUUID(), "AuthTester", "555666777", null);

            BindTicketService.Ticket ticket = tickets.issue(identity, "203.0.113.10").orElseThrow();
            assertTrue(tickets.find(ticket.raw()).isPresent());

            ChallengeIssued challenge = auth.requestCode(ticket.raw(), "auth@qq.com").get(10, TimeUnit.SECONDS);
            String code = mail.lastCode.get();
            assertEquals(6, code.length());

            String wrong = code.equals("000000") ? "000001" : "000000";
            AuthException wrongError = authFailure(() ->
                    auth.verify(challenge.challengeToken(), wrong, "12345678").get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.INVALID_CODE, wrongError.error());

            assertEquals("auth@qq.com",
                    auth.verify(challenge.challengeToken(), code, "12345678").get(10, TimeUnit.SECONDS).email());
            assertTrue(data.bindingCache().isBound(identity.key()));
            assertTrue(tickets.find(ticket.raw()).isEmpty());

            AuthException replay = authFailure(() ->
                    auth.verify(challenge.challengeToken(), code, "12345678").get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.CODE_EXPIRED, replay.error());
        }
    }

    @Test
    void invalidTicketAndRejectedDomainFailClosed() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("auth2.db"));
        try (DataStore data = new DataStore(config, Logger.getLogger("CarbonGuard-auth-test"))) {
            data.init(false);
            BindTicketService tickets = new BindTicketService(data, () -> config);
            AuthService auth = new AuthService(data, tickets, new FakeMail(), () -> config,
                    Logger.getLogger("CarbonGuard-auth-test"));
            AuthException invalid = authFailure(() ->
                    auth.requestCode("not-a-ticket", "auth@qq.com").get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.INVALID_TICKET, invalid.error());

            PlayerIdentity identity = PlayerIdentity.javaPlayer(UUID.randomUUID(), "JavaTester");
            BindTicketService.Ticket ticket = tickets.issue(identity, "203.0.113.11").orElseThrow();
            AuthException domain = authFailure(() ->
                    auth.requestCode(ticket.raw(), "user@example.com").get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.EMAIL_NOT_ALLOWED, domain.error());
            assertFalse(data.bindingCache().isBound(identity.key()));
        }
    }

    private static AuthException authFailure(Executable executable) throws Exception {
        ExecutionException error = assertThrows(ExecutionException.class, executable);
        assertTrue(error.getCause() instanceof AuthException, "expected AuthException, got " + error.getCause());
        return (AuthException) error.getCause();
    }

    private static final class FakeMail implements MailSender {
        private final AtomicReference<String> lastCode = new AtomicReference<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CompletableFuture<Void> sendVerification(String email, String playerName, String code, int ttlSeconds) {
            lastCode.set(code);
            return CompletableFuture.completedFuture(null);
        }
    }
}
