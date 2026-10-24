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
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.data.model.VerificationCode;
import xyz.chaihu.carbonguard.mail.MailSender;
import xyz.chaihu.carbonguard.mail.MailService;
import xyz.chaihu.carbonguard.service.AuthException.AuthError;
import xyz.chaihu.carbonguard.util.TimeUtil;
import xyz.chaihu.carbonguard.util.Tokens;

/**
 * Player-initiated binding on the public {@code /bind} page.
 *
 * <p>The player types an account ID (Bukkit UUID, Bedrock XUID, or the last seen
 * player name); {@link #lookup} resolves it to exactly one recorded identity, so the
 * server matches the UUID itself. A one-time e-mail code is then sent to the mailbox
 * whose local part equals the typed ID, which proves the visitor controls the
 * mailbox the account is bound to.</p>
 *
 * <p>The browser only holds an opaque request token; the code is stored as
 * PBKDF2-HMAC-SHA256 and is consumed inside the same transaction that writes the
 * binding, so it can never be replayed.</p>
 */
public final class AuthService {
    /** Accepted IDs: XUID, dashed/compact UUID, or a Java player name. */
    private static final Pattern PLAYER_ID = Pattern.compile("^[A-Za-z0-9_.-]{3,36}$");
    private static final Pattern XUID = Pattern.compile("^[1-9][0-9]{5,19}$");

    private final DataStore data;
    private final MailSender mail;
    private final Supplier<CarbonGuardConfig> configSupplier;
    private final Logger logger;
    /** Per (identity, ID) resend cooldown, reserved before the e-mail is sent. */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    public AuthService(DataStore data, MailSender mail, Supplier<CarbonGuardConfig> configSupplier,
                       Logger logger) {
        this.data = data;
        this.mail = mail;
        this.configSupplier = configSupplier;
        this.logger = logger;
    }

    /**
     * Resolves the ID typed on the {@code /bind} page.
     *
     * <p>Only identity facts are returned: no IPs, e-mail or ban data, because this
     * endpoint is public. Every request is rate limited by the API layer, so the
     * lookup cannot be used to walk the identity space quickly.</p>
     */
    public CompletableFuture<Lookup> lookup(String rawPlayerId) {
        String playerId;
        try {
            playerId = normalizePlayerId(rawPlayerId);
        } catch (AuthException e) {
            return failed(e);
        }
        CarbonGuardConfig config = configSupplier.get();
        return data.database().webAsync(connection -> {
            Optional<PlayerIdentity> optional = data.identities().findByPlayerId(connection, playerId);
            boolean bound = false;
            String boundAs = "";
            if (optional.isPresent()) {
                for (String key : optional.get().lookupKeys()) {
                    Optional<Binding> binding = data.bindings().find(connection, key);
                    if (binding.isPresent()) {
                        bound = true;
                        boundAs = binding.get().email();
                        break;
                    }
                }
            }
            return new Lookup(playerId, optional.map(PlayerIdentity::playerName).orElse(""),
                    optional.map(PlayerIdentity::uuid).map(java.util.UUID::toString).orElse(""),
                    optional.map(PlayerIdentity::effectiveXuid).orElse(""),
                    bound, boundAs, CarbonGuardConfig.Binding.qqMailbox(playerId),
                    CarbonGuardConfig.Binding.qqAvatarUrl(playerId), config.binding());
        }).exceptionallyCompose(error -> failed(unwrapAuth(error)));
    }

    /**
     * Sends a one-time code to the mailbox belonging to the typed ID.
     *
     * <p>The address is never taken from the browser: it is always
     * {@link CarbonGuardConfig.Binding#qqMailbox(String) ID@qq.com}. {@code requestedEmail}
     * is accepted only so the page can show a clear error when a visitor typed a
     * different mailbox; anything else is ignored.</p>
     */
    public CompletableFuture<ChallengeIssued> requestCode(String rawPlayerId, String requestedEmail) {
        String playerId;
        try {
            playerId = normalizePlayerId(rawPlayerId);
        } catch (AuthException e) {
            return failed(e);
        }
        CarbonGuardConfig config = configSupplier.get();
        String mailbox = CarbonGuardConfig.Binding.qqMailbox(playerId);
        if (!requestedEmail.isBlank() && !requestedEmail.equalsIgnoreCase(mailbox)) {
            return failed(new AuthException(AuthError.INVALID_EMAIL,
                    "绑定邮箱固定为 " + mailbox + "，请输入正确的 QQ 号。"));
        }
        if (!mail.available()) {
            return failed(new AuthException(AuthError.SMTP_UNAVAILABLE, "邮件服务暂不可用，请联系管理员。"));
        }
        long now = TimeUtil.now();
        if (cooldownUntil.size() > 10_000) {
            cooldownUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        int cooldownSeconds = config.emailVerify().requestCooldownSeconds();
        String cooldownKey = playerId.toLowerCase(Locale.ROOT);
        long[] active = {0L};
        cooldownUntil.compute(cooldownKey, (key, existing) -> {
            if (existing != null && existing > now) {
                active[0] = existing;
                return existing;
            }
            active[0] = 0L;
            // Reserve the slot before sending so concurrent requests cannot both send.
            return now + cooldownSeconds * 1_000L;
        });
        if (active[0] > now) {
            long seconds = Math.max(1, (active[0] - now + 999) / 1_000);
            return failed(new AuthException(AuthError.COOLDOWN, "请求过于频繁，请在 " + seconds + " 秒后重试。"));
        }

        String requestRaw = Tokens.random(32);
        String requestHash = Tokens.sha256(requestRaw);
        String code = Tokens.sixDigitCode();

        return data.database().webAsync(connection ->
                        data.identities().findByPlayerId(connection, playerId))
                .thenCompose(found -> {
                    if (found.isEmpty()) {
                        return failed(new AuthException(AuthError.ACCOUNT_NOT_FOUND,
                                "没有找到该 ID 对应的账号，请先进入过一次服务器。"));
                    }
                    // The request is re-validated against the database inside the
                    // transaction: another window may have bound the account meanwhile.
                    PlayerIdentity identity = found.get();
                    String emailKey = playerId.toLowerCase(Locale.ROOT);
                    long expiresAt = now + config.emailVerify().codeTtlSeconds() * 1_000L;
                    byte[] saltBytes = Tokens.randomBytes(16);
                    String salt = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(saltBytes);
                    String codeHash = Tokens.codeHash(code, saltBytes);
                    VerificationCode record = new VerificationCode(0L, requestHash, playerId,
                            identity.key(), identity.playerName(), emailKey, mailbox, salt, codeHash,
                            0, now, expiresAt, 0);
                    CompletableFuture<Long> insert = data.database().writeTransaction(
                            connection -> insertInTransaction(connection, identity, record, now));
                    return insert.thenCompose(id ->
                            mail.sendVerification(mailbox, identity.playerName(), code,
                                            config.emailVerify().codeTtlSeconds())
                                    .handle((sent, error) -> {
                                        if (error != null) {
                                            // The queued delete is best effort; the code expires anyway.
                                            data.database().write(connection ->
                                                    data.codes().deleteById(connection, id));
                                            throw new CompletionException(new AuthException(AuthError.SMTP_FAILED,
                                                    "验证码邮件发送失败，请稍后重试或联系管理员。"));
                                        }
                                        return issued(requestRaw, MailService.maskEmail(mailbox),
                                                expiresAt, config);
                                    }));
                })
                .exceptionallyCompose(error -> {
                    // A failed attempt must not keep the ID in cooldown.
                    cooldownUntil.put(cooldownKey, now);
                    return failed(unwrapAuth(error));
                });
    }

    /** Verifies the one-time mailbox code and writes the binding. */
    public CompletableFuture<Binding> verify(String rawRequest, String rawCode) {
        if (rawRequest == null || rawRequest.isBlank() || rawRequest.length() > 256) {
            return failed(new AuthException(AuthError.INVALID_REQUEST, "绑定会话不存在，请重新提交。"));
        }
        CarbonGuardConfig config = configSupplier.get();
        String requestHash = Tokens.sha256(rawRequest.trim());
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{6}")) {
            return failed(new AuthException(AuthError.INVALID_CODE, "请输入 6 位数字验证码。"));
        }

        Optional<VerificationCode> preloaded;
        try {
            preloaded = data.database().webNow(connection ->
                    data.codes().findByRequestHash(connection, requestHash));
        } catch (Exception e) {
            return failed(new AuthException(AuthError.INTERNAL, "服务器内部错误，请稍后重试。"));
        }
        if (preloaded.isEmpty()) {
            return failed(new AuthException(AuthError.CODE_EXPIRED, "验证会话不存在或已过期，请重新提交。"));
        }
        VerificationCode challenge = preloaded.get();
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
        final String computedHash = computed;

        // PBKDF2 is deliberately outside the write transaction: the single SQLite
        // writer must not hold the write lock for ~100ms per verification attempt.
        return data.database().writeTransaction(connection ->
                        verifyInTransaction(connection, challenge, computedHash, TimeUtil.now()))
                .thenApply(attempt -> {
                    if (attempt.status() == VerifyStatus.SUCCESS) {
                        Binding binding = attempt.binding();
                        data.bindingCache().put(binding);
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

    /**
     * Records the request. Creating a new code invalidates every other pending request
     * for the same identity, so at most one code can ever be pending per account.
     */
    private long insertInTransaction(Connection connection, PlayerIdentity identity,
                                     VerificationCode record, long now) throws SQLException {
        for (String key : identity.lookupKeys()) {
            if (data.bindings().find(connection, key).isPresent()) {
                throw new AuthException(AuthError.ALREADY_BOUND, "该账号已经完成绑定，无需重复操作。");
            }
        }
        if (data.bindings().emailTaken(connection, record.emailKey(), identity.key())) {
            throw new AuthException(AuthError.ALREADY_BOUND, "该邮箱已绑定其他游戏账号。");
        }
        data.codes().consumeForIdentity(connection, identity.key(), now);
        data.identities().setPlayerId(connection, identity.key(), record.playerId());
        return data.codes().insert(connection, record);
    }

    private VerifyAttempt verifyInTransaction(Connection connection, VerificationCode preloaded,
                                              String computed, long now) throws SQLException {
        Optional<VerificationCode> optional = data.codes().findActive(connection, preloaded.id(), now);
        if (optional.isEmpty()) {
            return VerifyAttempt.error(VerifyStatus.EXPIRED, "验证码已使用或已过期，请重新获取。");
        }
        VerificationCode code = optional.get();
        CarbonGuardConfig config = configSupplier.get();
        if (code.attempts() >= config.emailVerify().maxAttempts()) {
            data.codes().consume(connection, code.id(), now);
            return VerifyAttempt.error(VerifyStatus.TOO_MANY, "尝试次数过多，请重新获取验证码。");
        }
        if (!Tokens.constantTimeEquals(computed, code.codeHash())) {
            int attempts = data.codes().incrementAttempts(connection, code.id());
            if (attempts >= config.emailVerify().maxAttempts()) {
                data.codes().consume(connection, code.id(), now);
                return VerifyAttempt.error(VerifyStatus.TOO_MANY, "验证码错误次数过多，该验证码已失效。");
            }
            int remaining = config.emailVerify().maxAttempts() - attempts;
            return VerifyAttempt.error(VerifyStatus.INVALID, "验证码不正确，还可尝试 " + remaining + " 次。");
        }
        // Correct code: consume first so it can never be replayed, even if a later
        // check (already bound, e-mail taken) fails.
        if (!data.codes().consume(connection, code.id(), now)) {
            return VerifyAttempt.error(VerifyStatus.EXPIRED, "验证码已使用，请重新获取。");
        }
        Optional<PlayerIdentity> identity = data.identities().findByKey(connection, code.identityKey());
        if (identity.isEmpty()) {
            return VerifyAttempt.error(VerifyStatus.IDENTITY_MISSING, "该账号记录已不存在，请重新进入服务器后再试。");
        }
        // Re-match the typed ID so a rename between request and verification cannot
        // silently bind a different account.
        if (!identity.get().matchId(code.playerId())) {
            return VerifyAttempt.error(VerifyStatus.IDENTITY_MISSING, "ID 与账号不再匹配，请重新提交。");
        }
        for (String key : identity.get().lookupKeys()) {
            if (data.bindings().find(connection, key).isPresent()) {
                return VerifyAttempt.error(VerifyStatus.ALREADY_BOUND, "该账号已经完成绑定。");
            }
        }
        if (data.bindings().emailTaken(connection, code.emailKey(), code.identityKey())) {
            return VerifyAttempt.error(VerifyStatus.ALREADY_BOUND, "该邮箱已绑定其他游戏账号。");
        }
        // Defence in depth: the stored mailbox must still be the QQ mailbox of the
        // stored ID, even if a row was written by an older release.
        String expectedMailbox = CarbonGuardConfig.Binding.qqMailbox(code.playerId());
        if (!CarbonGuardConfig.Binding.isQqMailbox(code.email())
                || !CarbonGuardConfig.Binding.qqMailLocalPart(code.email()).equalsIgnoreCase(code.playerId())) {
            logger.warning("绑定请求 " + code.playerId() + " 的邮箱与 ID 不匹配（" + code.email()
                    + "），已按 " + expectedMailbox + " 重新计算");
        }
        long verifiedAt = TimeUtil.now();
        Binding binding = new Binding(code.identityKey(), expectedMailbox, code.playerId(),
                code.emailKey(), verifiedAt);
        data.bindings().save(connection, binding, verifiedAt);
        data.identities().setPlayerId(connection, code.identityKey(), code.playerId());
        return new VerifyAttempt(VerifyStatus.SUCCESS, binding, 0, "");
    }

    public static String normalizePlayerId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() < 3 || value.length() > 36 || !PLAYER_ID.matcher(value).matches()) {
            throw new AuthException(AuthError.INVALID_REQUEST,
                    "请输入 3-36 位账号 ID（游戏内 UUID、基岩版 XUID 或游戏名）。");
        }
        return value;
    }

    private ChallengeIssued issued(String requestToken, String maskedEmail, long expiresAt,
                                   CarbonGuardConfig config) {
        return new ChallengeIssued(requestToken, expiresAt, maskedEmail,
                config.emailVerify().codeTtlSeconds(), config.emailVerify().requestCooldownSeconds());
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

    public boolean isXuid(String playerId) {
        return playerId != null && XUID.matcher(playerId.trim()).matches();
    }

    private enum VerifyStatus {
        SUCCESS, INVALID, EXPIRED, TOO_MANY, ALREADY_BOUND, EMAIL_TAKEN, IDENTITY_MISSING
    }

    private record VerifyAttempt(VerifyStatus status, Binding binding, int remaining, String message) {
        static VerifyAttempt error(VerifyStatus status, String message) {
            return new VerifyAttempt(status, null, 0, message);
        }
    }

    /**
     * Public view of the resolved account. {@code codeEmail} is the fixed
     * {@code <ID>@qq.com} mailbox the code goes to and {@code avatarUrl} is the QQ avatar
     * for numeric IDs, so the visitor can confirm the right account; nothing else about
     * the account leaves the server.
     */
    public record Lookup(String playerId, String playerName, String uuid, String xuid, boolean bound,
                         String boundEmail, String codeEmail, String avatarUrl,
                         CarbonGuardConfig.Binding binding) {
    }

    public record ChallengeIssued(String requestToken, long expiresAt, String maskedEmail,
                                  int ttlSeconds, int cooldownSeconds) {
        public ChallengeIssued {
            Objects.requireNonNull(requestToken);
        }
    }
}
