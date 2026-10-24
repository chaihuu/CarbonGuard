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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.Binding;
import xyz.chaihu.carbonguard.data.model.EmailChallenge;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.mail.MailSender;
import xyz.chaihu.carbonguard.mail.MailService;
import xyz.chaihu.carbonguard.service.AuthException.AuthError;
import xyz.chaihu.carbonguard.util.TimeUtil;
import xyz.chaihu.carbonguard.util.Tokens;

/**
 * Public e-mail verification and QQ binding flow.
 *
 * <p>The ticket proves control of the in-game identity, the e-mail code proves control
 * of the mailbox, and the challenge token is single-use. A successful code comparison
 * immediately consumes the challenge inside the same transaction that writes the
 * binding.</p>
 */
public final class AuthService {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,}$");
    private static final Pattern QQ = Pattern.compile("^[1-9][0-9]{4,11}$");
    private final DataStore data;
    private final BindTicketService tickets;
    private final MailSender mail;
    private final Supplier<CarbonGuardConfig> configSupplier;
    private final Logger logger;
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    public AuthService(DataStore data, BindTicketService tickets, MailSender mail,
                       Supplier<CarbonGuardConfig> configSupplier, Logger logger) {
        this.data = data;
        this.tickets = tickets;
        this.mail = mail;
        this.configSupplier = configSupplier;
        this.logger = logger;
    }

    public CompletableFuture<ChallengeIssued> requestCode(String rawTicket, String rawEmail) {
        Optional<BindTicketService.Ticket> optional = tickets.find(rawTicket);
        if (optional.isEmpty()) {
            return failed(new AuthException(AuthError.INVALID_TICKET, "绑定链接无效或已过期，请重新进入服务器获取新链接。"));
        }
        BindTicketService.Ticket ticket = optional.get();
        CarbonGuardConfig config = configSupplier.get();
        if (isBound(ticket.identity())) {
            return failed(new AuthException(AuthError.ALREADY_BOUND, "该身份已经完成绑定。"));
        }
        String email;
        try {
            email = normalizeEmail(rawEmail, config);
        } catch (AuthException e) {
            return failed(e);
        }
        if (!mail.available()) {
            return failed(new AuthException(AuthError.SMTP_UNAVAILABLE, "邮件服务暂不可用，请联系管理员。"));
        }
        long now = TimeUtil.now();
        if (cooldownUntil.size() > 10_000) {
            cooldownUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        long[] activeCooldown = {0L};
        int cooldownSeconds = config.emailVerify().requestCooldownSeconds();
        cooldownUntil.compute(ticket.identity().key(), (key, existing) -> {
            if (existing != null && existing > now) {
                activeCooldown[0] = existing;
                return existing;
            }
            activeCooldown[0] = 0L;
            // Reserve the slot before sending. Without this, concurrent requests can
            // both pass the check and send multiple e-mails.
            return now + cooldownSeconds * 1_000L;
        });
        if (activeCooldown[0] > now) {
            long seconds = Math.max(1, (activeCooldown[0] - now + 999) / 1_000);
            return failed(new AuthException(AuthError.COOLDOWN, "请求过于频繁，请在 " + seconds + " 秒后重试。"));
        }

        String challengeRaw = Tokens.random(32);
        String challengeHash = Tokens.sha256(challengeRaw);
        byte[] saltBytes = Tokens.randomBytes(16);
        String salt = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(saltBytes);
        String code = Tokens.sixDigitCode();
        String codeHash = Tokens.codeHash(code, saltBytes);
        long expiresAt = now + config.emailVerify().codeTtlSeconds() * 1_000L;
        EmailChallenge challenge = new EmailChallenge(challengeHash, ticket.hash(), ticket.identity().key(),
                ticket.identity().playerName(), email, salt, codeHash, 0, now, expiresAt, 0);

        CompletableFuture<Void> insert = data.database().writeTransaction(connection -> {
            for (String key : ticket.identity().lookupKeys()) {
                if (data.bindings().find(connection, key).isPresent()) {
                    throw new AuthException(AuthError.ALREADY_BOUND, "该身份已经完成绑定。");
                }
            }
            if (data.bindings().emailTaken(connection, email, ticket.identity().key())) {
                throw new AuthException(AuthError.EMAIL_TAKEN, "该邮箱已绑定其他游戏账号。");
            }
            data.identities().upsert(connection, ticket.identity(), ticket.ip(), now);
            data.verification().insertChallenge(connection, challenge);
            return null;
        });

        return insert.thenCompose(ignored ->
                        mail.sendVerification(email, ticket.identity().playerName(), code,
                                        config.emailVerify().codeTtlSeconds())
                                .handle((sent, error) -> {
                                    if (error != null) {
                                        data.database().write(connection ->
                                                data.verification().deleteChallenge(connection, challengeHash));
                                        throw new CompletionException(new AuthException(AuthError.SMTP_FAILED,
                                                "验证码邮件发送失败，请稍后重试或联系管理员。"));
                                    }
                                    return new ChallengeIssued(challengeRaw, expiresAt,
                                            MailService.maskEmail(email), config.emailVerify().codeTtlSeconds(),
                                            config.emailVerify().requestCooldownSeconds());
                                }))
                .exceptionallyCompose(error -> {
                    // A failed attempt must not keep the identity in cooldown.
                    cooldownUntil.put(ticket.identity().key(), now);
                    return failed(unwrapAuth(error));
                });
    }

    public CompletableFuture<Binding> verify(String rawChallenge, String rawCode, String rawQq) {
        if (rawChallenge == null || rawChallenge.isBlank()
                || rawCode == null || !rawCode.trim().matches("\\d{6}")) {
            return failed(new AuthException(AuthError.INVALID_CODE, "请输入 6 位数字验证码。"));
        }
        String qq = rawQq == null ? "" : rawQq.trim();
        if (!QQ.matcher(qq).matches()) {
            return failed(new AuthException(AuthError.INVALID_QQ, "请输入 5-12 位有效 QQ 号。"));
        }
        String challengeHash = Tokens.sha256(rawChallenge.trim());
        String code = rawCode.trim();
        CarbonGuardConfig config = configSupplier.get();

        Optional<EmailChallenge> preloaded;
        try {
            preloaded = data.database().coreNow(connection ->
                    data.verification().findChallenge(connection, challengeHash));
        } catch (Exception e) {
            return failed(new AuthException(AuthError.INTERNAL, "服务器内部错误，请稍后重试。"));
        }
        if (preloaded.isEmpty()) {
            return failed(new AuthException(AuthError.CODE_EXPIRED, "验证码不存在或已过期，请重新获取。"));
        }
        EmailChallenge challenge = preloaded.get();
        if (!challenge.usable(TimeUtil.now())) {
            return failed(new AuthException(AuthError.CODE_EXPIRED, "验证码已过期，请重新获取。"));
        }
        if (challenge.attempts() >= config.emailVerify().maxAttempts()) {
            return failed(new AuthException(AuthError.TOO_MANY_ATTEMPTS, "尝试次数过多，请重新获取验证码。"));
        }
        String computed;
        try {
            computed = Tokens.codeHash(code, Tokens.decode(challenge.codeSalt()));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "验证码 PBKDF2 校验失败", e);
            return failed(new AuthException(AuthError.INTERNAL, "服务器内部错误，请稍后重试。"));
        }

        // PBKDF2 is deliberately outside the write transaction: the single SQLite
        // writer must not hold the write lock for ~100ms per verification attempt.
        return data.database().writeTransaction(connection -> verifyInTransaction(connection, challengeHash,
                        qq, TimeUtil.now(), computed))
                .thenApply(attempt -> {
                    if (attempt.status() == VerifyStatus.SUCCESS) {
                        Binding binding = attempt.binding();
                        data.bindingCache().put(binding);
                        tickets.forget(attempt.ticketHash());
                        return binding;
                    }
                    throw new AuthException(switch (attempt.status()) {
                        case INVALID -> AuthError.INVALID_CODE;
                        case EXPIRED -> AuthError.CODE_EXPIRED;
                        case TOO_MANY -> AuthError.TOO_MANY_ATTEMPTS;
                        case ALREADY_BOUND -> AuthError.ALREADY_BOUND;
                        case EMAIL_TAKEN -> AuthError.EMAIL_TAKEN;
                        case IDENTITY_MISSING -> AuthError.INVALID_TICKET;
                        default -> AuthError.INTERNAL;
                    }, attempt.message());
                })
                .exceptionallyCompose(error -> failed(unwrapAuth(error)));
    }

    private VerifyAttempt verifyInTransaction(Connection connection, String challengeHash,
                                              String qq, long now, String computed) throws SQLException {
        Optional<EmailChallenge> optional = data.verification().findChallenge(connection, challengeHash);
        if (optional.isEmpty()) {
            return VerifyAttempt.error(VerifyStatus.EXPIRED, "验证码不存在或已过期，请重新获取。");
        }
        EmailChallenge challenge = optional.get();
        if (!challenge.usable(now)) {
            return VerifyAttempt.error(VerifyStatus.EXPIRED, "验证码已过期，请重新获取。");
        }
        CarbonGuardConfig config = configSupplier.get();
        if (challenge.attempts() >= config.emailVerify().maxAttempts()) {
            data.verification().consumeChallenge(connection, challengeHash, now);
            return VerifyAttempt.error(VerifyStatus.TOO_MANY, "尝试次数过多，请重新获取验证码。");
        }
        if (!Tokens.constantTimeEquals(computed, challenge.codeHash())) {
            int attempts = data.verification().incrementAttempts(connection, challengeHash);
            if (attempts >= config.emailVerify().maxAttempts()) {
                data.verification().consumeChallenge(connection, challengeHash, now);
                return VerifyAttempt.error(VerifyStatus.TOO_MANY,
                        "验证码错误次数过多，该验证码已失效。");
            }
            int remaining = config.emailVerify().maxAttempts() - attempts;
            return VerifyAttempt.error(VerifyStatus.INVALID, "验证码不正确，还可尝试 " + remaining + " 次。");
        }

        // Code is correct: consume it before any later failure, so it can never be replayed.
        if (!data.verification().consumeChallenge(connection, challengeHash, now)) {
            return VerifyAttempt.error(VerifyStatus.EXPIRED, "验证码已使用，请重新获取。");
        }
        Optional<PlayerIdentity> identity = data.identities().findByKey(connection, challenge.identityKey());
        if (identity.isEmpty()) {
            return VerifyAttempt.error(VerifyStatus.IDENTITY_MISSING, "身份记录不存在，请重新进入服务器。");
        }
        for (String key : identity.get().lookupKeys()) {
            if (data.bindings().find(connection, key).isPresent()) {
                return VerifyAttempt.error(VerifyStatus.ALREADY_BOUND, "该身份已经完成绑定。");
            }
        }
        if (data.bindings().emailTaken(connection, challenge.email(), challenge.identityKey())) {
            return VerifyAttempt.error(VerifyStatus.EMAIL_TAKEN, "该邮箱已绑定其他游戏账号，请重新获取验证码。");
        }
        if (!config.emailVerify().emailAllowed(challenge.email())) {
            return VerifyAttempt.error(VerifyStatus.EMAIL_TAKEN, "该邮箱域名不在允许列表中。");
        }
        long verifiedAt = TimeUtil.now();
        Binding binding = new Binding(challenge.identityKey(), challenge.email(), qq, verifiedAt);
        data.bindings().save(connection, binding, verifiedAt);
        data.verification().consumeTicket(connection, challenge.ticketHash(), verifiedAt);
        return new VerifyAttempt(VerifyStatus.SUCCESS, binding, challenge.ticketHash(), 0, "");
    }

    private boolean isBound(PlayerIdentity identity) {
        for (String key : identity.lookupKeys()) {
            if (data.bindingCache().isBound(key)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeEmail(String raw, CarbonGuardConfig config) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new AuthException(AuthError.INVALID_EMAIL, "邮箱格式不正确。");
        }
        if (!config.emailVerify().emailAllowed(email)) {
            throw new AuthException(AuthError.EMAIL_NOT_ALLOWED, "请使用允许的 QQ 邮箱域名。");
        }
        return email;
    }

    private static <T> CompletableFuture<T> failed(AuthException error) {
        return CompletableFuture.failedFuture(error);
    }

    public static AuthException unwrapAuth(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof AuthException auth) {
            return auth;
        }
        return new AuthException(AuthError.INTERNAL, "服务器内部错误，请稍后重试。");
    }

    public void logInternal(Throwable error) {
        AuthException auth = unwrapAuth(error);
        if (auth.error() == AuthError.INTERNAL) {
            logger.log(Level.WARNING, "绑定流程内部错误", error);
        }
    }

    private enum VerifyStatus {
        SUCCESS, INVALID, EXPIRED, TOO_MANY, ALREADY_BOUND, EMAIL_TAKEN, IDENTITY_MISSING
    }

    private record VerifyAttempt(VerifyStatus status, Binding binding, String ticketHash,
                                 int remaining, String message) {
        static VerifyAttempt error(VerifyStatus status, String message) {
            return new VerifyAttempt(status, null, null, 0, message);
        }
    }

    public record ChallengeIssued(String challengeToken, long expiresAt, String maskedEmail,
                                  int ttlSeconds, int cooldownSeconds) {
        public ChallengeIssued {
            Objects.requireNonNull(challengeToken);
        }
    }
}
