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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.Binding;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.mail.MailSender;
import xyz.chaihu.carbonguard.service.AuthException;
import xyz.chaihu.carbonguard.service.AuthService;
import xyz.chaihu.carbonguard.service.AuthService.ChallengeIssued;
import xyz.chaihu.carbonguard.util.TimeUtil;

/**
 * Covers the player-initiated binding flow: type an ID on /bind, the server matches the
 * account (UUID/XUID), a one-time code goes to {@code <id>@qq.com}, and the code is
 * consumed exactly once.
 */
class AuthFlowTest {
    @TempDir
    Path tempDir;

    @Test
    void typedPlayerNameResolvesToTheRecordedUuidAndBinds() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("auth.db"));
        try (DataStore data = new DataStore(config, Logger.getLogger("CarbonGuard-auth-test"))) {
            data.init(false);
            PlayerIdentity identity = PlayerIdentity.javaPlayer(UUID.randomUUID(), "BindTester");
            recordIdentity(data, identity);

            FakeMail mail = new FakeMail();
            AuthService auth = new AuthService(data, mail, () -> config,
                    Logger.getLogger("CarbonGuard-auth-test"));

            AuthService.Lookup lookup = auth.lookup("BindTester").get(10, TimeUnit.SECONDS);
            assertFalse(lookup.bound());
            assertEquals(identity.uuid().toString(), lookup.uuid());
            // The mailbox is always derived from the typed ID, never from the browser.
            assertEquals("bindtester@qq.com", lookup.codeEmail());
            assertEquals("", lookup.avatarUrl(), "non-numeric IDs get no QQ avatar");

            ChallengeIssued challenge = auth.requestCode("BindTester", "bindtester@qq.com")
                    .get(10, TimeUnit.SECONDS);
            String code = mail.lastCode.get();
            assertEquals(6, code.length());
            assertEquals("bindtester@qq.com", mail.lastRecipient.get());

            String wrong = code.equals("000000") ? "000001" : "000000";
            AuthException wrongError = authFailure(() ->
                    auth.verify(challenge.requestToken(), wrong).get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.INVALID_CODE, wrongError.error());

            Binding binding = auth.verify(challenge.requestToken(), code).get(10, TimeUnit.SECONDS);
            assertEquals(identity.key(), binding.identityKey());
            assertEquals("bindtester@qq.com", binding.email());
            assertEquals("bindtester", binding.emailKey());
            assertTrue(data.bindingCache().isBound(identity.key()));
            assertTrue(auth.lookup("BindTester").get(10, TimeUnit.SECONDS).bound());

            AuthException replay = authFailure(() ->
                    auth.verify(challenge.requestToken(), code).get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.CODE_EXPIRED, replay.error());
        }
    }

    @Test
    void xuidAndUuidBothResolveToTheSameBedrockIdentity() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("auth-id.db"));
        try (DataStore data = new DataStore(config, Logger.getLogger("CarbonGuard-auth-test"))) {
            data.init(false);
            PlayerIdentity identity = PlayerIdentity.xuid(UUID.randomUUID(), "BedrockTester",
                    "555666777", null);
            recordIdentity(data, identity);
            AuthService auth = new AuthService(data, new FakeMail(), () -> config,
                    Logger.getLogger("CarbonGuard-auth-test"));

            assertEquals(identity.uuid().toString(),
                    auth.lookup("555666777").get(10, TimeUnit.SECONDS).uuid());
            assertEquals(identity.uuid().toString(),
                    auth.lookup(identity.uuid().toString()).get(10, TimeUnit.SECONDS).uuid());
            assertEquals(identity.uuid().toString(),
                    auth.lookup(identity.uuid().toString().replace("-", "")).get(10, TimeUnit.SECONDS).uuid());
            assertEquals("555666777", auth.lookup("555666777").get(10, TimeUnit.SECONDS).xuid());
            assertEquals("555666777@qq.com",
                    auth.lookup("555666777").get(10, TimeUnit.SECONDS).codeEmail());
            assertEquals("https://q1.qlogo.cn/g?b=qq&nk=555666777&s=100",
                    auth.lookup("555666777").get(10, TimeUnit.SECONDS).avatarUrl(), "numeric IDs get a QQ avatar");

            // A well-formed but unknown UUID is a lookup miss, not a malformed request.
            AuthService.Lookup unknown = auth.lookup("11111111-2222-3333-4444-555555555555")
                    .get(10, TimeUnit.SECONDS);
            assertEquals("11111111-2222-3333-4444-555555555555", unknown.playerId());
            assertEquals("", unknown.uuid());
            assertFalse(unknown.bound());
        }
    }

    @Test
    void emailMustMatchTheTypedIdAndUnknownIdsFail() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("auth2.db"));
        try (DataStore data = new DataStore(config, Logger.getLogger("CarbonGuard-auth-test"))) {
            data.init(false);
            PlayerIdentity identity = PlayerIdentity.javaPlayer(UUID.randomUUID(), "JavaTester");
            recordIdentity(data, identity);
            AuthService auth = new AuthService(data, new FakeMail(), () -> config,
                    Logger.getLogger("CarbonGuard-auth-test"));

            AuthException mismatchEmail = authFailure(() ->
                    auth.requestCode("JavaTester", "someoneelse@qq.com").get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.INVALID_EMAIL, mismatchEmail.error());

            AuthException wrongDomain = authFailure(() ->
                    auth.requestCode("JavaTester", "javatesters@gmail.com").get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.INVALID_EMAIL, wrongDomain.error());

            AuthException missing = authFailure(() ->
                    auth.requestCode("NoSuchPlayer", "nosuchplayer@qq.com").get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.ACCOUNT_NOT_FOUND, missing.error());

            AuthException invalidId = authFailure(() ->
                    auth.lookup("a b").get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.INVALID_REQUEST, invalidId.error());
            assertFalse(data.bindingCache().isBound(identity.key()));
        }
    }

    @Test
    void alreadyBoundAccountsAreRejected() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("auth3.db"));
        try (DataStore data = new DataStore(config, Logger.getLogger("CarbonGuard-auth-test"))) {
            data.init(false);
            PlayerIdentity identity = PlayerIdentity.javaPlayer(UUID.randomUUID(), "BoundTester");
            recordIdentity(data, identity);
            long now = TimeUtil.now();
            data.database().write(connection -> data.bindings().save(connection,
                    new Binding(identity.key(), "boundtester@qq.com", identity.uuid().toString(),
                            "boundtester", now), now)).get(5, TimeUnit.SECONDS);
            data.refreshCaches();

            AuthService auth = new AuthService(data, new FakeMail(), () -> config,
                    Logger.getLogger("CarbonGuard-auth-test"));
            assertTrue(auth.lookup("boundtester").get(10, TimeUnit.SECONDS).bound());

            AuthException already = authFailure(() ->
                    auth.requestCode("BoundTester", "boundtester@qq.com").get(10, TimeUnit.SECONDS));
            assertEquals(AuthException.AuthError.ALREADY_BOUND, already.error());
        }
    }

    @Test
    void mailboxIsAlwaysTheIdAtQqDotCom() throws Exception {
        CarbonGuardConfig config = TestConfig.create(tempDir.resolve("auth4.db"));
        try (DataStore data = new DataStore(config, Logger.getLogger("CarbonGuard-auth-test"))) {
            data.init(false);
            PlayerIdentity identity = PlayerIdentity.javaPlayer(UUID.randomUUID(), "ConfirmTester");
            recordIdentity(data, identity);
            FakeMail mail = new FakeMail();
            AuthService auth = new AuthService(data, mail, () -> config,
                    Logger.getLogger("CarbonGuard-auth-test"));

            // Even when the page claims another mailbox the server uses the ID.
            ChallengeIssued challenge = auth.requestCode("ConfirmTester", "")
                    .get(10, TimeUnit.SECONDS);
            assertEquals("confirmtester@qq.com", mail.lastRecipient.get());
            Binding binding = auth.verify(challenge.requestToken(), mail.lastCode.get())
                    .get(10, TimeUnit.SECONDS);
            assertEquals("confirmtester@qq.com", binding.email());
            assertEquals("confirmtester", binding.emailKey());
        }
    }

    private static void recordIdentity(DataStore data, PlayerIdentity identity) throws Exception {
        long now = TimeUtil.now();
        data.database().write(connection ->
                data.identities().upsert(connection, identity, "203.0.113.10", now))
                .get(5, TimeUnit.SECONDS);
    }

    private static AuthException authFailure(Executable executable) throws Exception {
        ExecutionException error = assertThrows(ExecutionException.class, executable);
        assertTrue(error.getCause() instanceof AuthException, "expected AuthException, got " + error.getCause());
        return (AuthException) error.getCause();
    }

    private static final class FakeMail implements MailSender {
        private final AtomicReference<String> lastCode = new AtomicReference<>();
        private final AtomicReference<String> lastRecipient = new AtomicReference<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CompletableFuture<Void> sendVerification(String email, String playerName, String code, int ttlSeconds) {
            lastCode.set(code);
            lastRecipient.set(email);
            return CompletableFuture.completedFuture(null);
        }
    }
}
