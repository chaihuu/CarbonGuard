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

package xyz.chaihu.carbonguard.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import xyz.chaihu.carbonguard.util.CidrMatcher;
import xyz.chaihu.carbonguard.util.IpUtil;
import xyz.chaihu.carbonguard.util.Tokens;

/** Reads {@code config.yml} into the immutable {@link CarbonGuardConfig}. */
public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static LoadResult loadFile(Path configFile, Path dataFolder)
            throws IOException, InvalidConfigurationException {
        if (!Files.exists(configFile)) {
            throw new IOException("配置文件不存在: " + configFile);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(configFile.toFile());
        return load(yaml, dataFolder, configFile);
    }

    public static LoadResult load(FileConfiguration cfg, Path dataFolder) {
        return load(cfg, dataFolder, null);
    }

    private static LoadResult load(FileConfiguration cfg, Path dataFolder, Path configFile) {
        List<String> warnings = new ArrayList<>();

        int version = clamp(cfg.getInt("config-version", 1), 1, 1, "config-version", warnings);
        boolean debug = cfg.getBoolean("debug", false);

        String fileName = string(cfg, "storage.sqlite-file", "carbonguard.db");
        Path dbPath;
        try {
            dbPath = Path.of(fileName);
            if (!dbPath.isAbsolute()) {
                dbPath = dataFolder.resolve(dbPath);
            }
            dbPath = dbPath.normalize();
        } catch (RuntimeException e) {
            warnings.add("storage.sqlite-file 非法，回退到 carbonguard.db: " + e.getMessage());
            dbPath = dataFolder.resolve("carbonguard.db");
        }

        int loginPool = clamp(cfg.getInt("storage.pool.login-maximum-pool-size", 4), 1, 32,
                "storage.pool.login-maximum-pool-size", warnings);
        int webPool = clamp(cfg.getInt("storage.pool.web-maximum-pool-size", 4), 1, 32,
                "storage.pool.web-maximum-pool-size", warnings);
        int poolTimeout = clamp(cfg.getInt("storage.pool.connection-timeout-ms", 5_000), 1_000, 60_000,
                "storage.pool.connection-timeout-ms", warnings);
        int cacheRefresh = clamp(cfg.getInt("storage.cache-refresh-seconds", 60), 5, 3_600,
                "storage.cache-refresh-seconds", warnings);

        boolean ipLimitEnable = cfg.getBoolean("ip-limit.enable", true);
        int maxAccounts = clamp(cfg.getInt("ip-limit.max-accounts-per-ip", 3), 1, 10_000,
                "ip-limit.max-accounts-per-ip", warnings);
        int grace = clamp(cfg.getInt("ip-limit.grace-threshold", 1), 0, 1_000,
                "ip-limit.grace-threshold", warnings);
        IpLimitMode mode = enumValue(IpLimitMode.class, cfg.getString("ip-limit.mode", "REJECT"),
                IpLimitMode.REJECT, "ip-limit.mode", warnings);
        List<String> ipLimitIps = stringList(cfg, "ip-limit.whitelist-ips");
        List<String> ipLimitPlayers = stringList(cfg, "ip-limit.whitelist-players");
        validateCidrs(ipLimitIps, "ip-limit.whitelist-ips", warnings);

        boolean banEnable = cfg.getBoolean("ban.enable", true);
        boolean idBan = cfg.getBoolean("ban.id-uid-xuid.enable", true);
        boolean assocEnable = cfg.getBoolean("ban.ip-association.enable", true);
        IpAssociationAction assocAction = enumValue(IpAssociationAction.class,
                cfg.getString("ban.ip-association.action", "PENDING_REVIEW"),
                IpAssociationAction.PENDING_REVIEW, "ban.ip-association.action", warnings);
        int ttlHours = clamp(cfg.getInt("ban.ip-association.ttl-hours", 168), 0, 87_600,
                "ban.ip-association.ttl-hours", warnings);

        boolean webEnable = cfg.getBoolean("web.enable", true);
        String bindAddress = string(cfg, "web.bind-address", "127.0.0.1").trim();
        if (bindAddress.startsWith("[") && bindAddress.endsWith("]")) {
            bindAddress = bindAddress.substring(1, bindAddress.length() - 1);
        }
        if (!IpUtil.isLiteral(bindAddress) && !bindAddress.equalsIgnoreCase("localhost")) {
            warnings.add("web.bind-address 不是 IP 字面量，已回退到 127.0.0.1。");
            bindAddress = "127.0.0.1";
        }
        if (!bindAddress.equals("127.0.0.1") && !bindAddress.equals("::1")
                && !bindAddress.equalsIgnoreCase("localhost") && !bindAddress.equals("0.0.0.0")
                && !bindAddress.equals("::")) {
            warnings.add("web.bind-address 不是回环或通配地址；请确认防火墙与反向代理已正确配置。");
        }
        int webPort = clamp(cfg.getInt("web.port", 8_080), 1, 65_535, "web.port", warnings);
        String rawAdminKey = string(cfg, "web.admin-key", "");
        String adminKey = expandSecret(rawAdminKey);
        boolean adminKeyGenerated = false;
        if (webEnable && adminKey.isBlank()) {
            if (isEnvReference(rawAdminKey)) {
                warnings.add(rawAdminKey + " 对应的环境变量为空；已自动生成随机管理密钥。");
            }
            adminKey = autoGenerateAdminKey(configFile, warnings);
            adminKeyGenerated = true;
        }
        String publicUrl = string(cfg, "web.public-url", "");
        if (publicUrl.isBlank()) {
            publicUrl = "http://" + IpUtil.displayHost(bindAddress) + ":" + webPort;
            if (bindAddress.equals("0.0.0.0") || bindAddress.equals("::")) {
                warnings.add("web.bind-address 为通配地址但 web.public-url 为空；踢出消息中的链接会指向回环地址，远程玩家无法打开。请显式填写 public-url。");
            }
        } else {
            publicUrl = publicUrl.replaceAll("/+$", "");
            if (!validHttpUrl(publicUrl)) {
                warnings.add("web.public-url 不是合法的 http(s) URL，已回退到监听地址。");
                publicUrl = "http://" + IpUtil.displayHost(bindAddress) + ":" + webPort;
            } else {
                URI uri = URI.create(publicUrl);
                String path = uri.getPath();
                String query = uri.getRawQuery();
                String fragment = uri.getRawFragment();
                if ((path != null && !path.isEmpty() && !"/".equals(path))
                        || (query != null && !query.isBlank())
                        || (fragment != null && !fragment.isBlank())) {
                    warnings.add("web.public-url 含子路径/查询/片段；面板按域名根路径部署，已忽略这些部分。");
                    publicUrl = uri.getScheme() + "://" + uri.getRawAuthority();
                }
            }
        }
        int webRate = clamp(cfg.getInt("web.rate-limit-per-minute", 120), 1, 100_000,
                "web.rate-limit-per-minute", warnings);
        int publicRate = clamp(cfg.getInt("web.public-rate-limit-per-minute", 12), 1, 100_000,
                "web.public-rate-limit-per-minute", warnings);
        boolean trustXff = cfg.getBoolean("web.trust-forwarded-for", false);
        if (trustXff) {
            warnings.add("web.trust-forwarded-for=true：仅可在可信反向代理之后启用，直连会允许伪造来源 IP。");
        }

        boolean smtpEnable = cfg.getBoolean("smtp.enable", true);
        String smtpHost = string(cfg, "smtp.host", "smtp.qq.com");
        int smtpPort = clamp(cfg.getInt("smtp.port", 587), 1, 65_535, "smtp.port", warnings);
        String smtpUser = string(cfg, "smtp.username", "");
        String smtpPassword = expandSecret(string(cfg, "smtp.password", ""));
        String smtpFrom = string(cfg, "smtp.from", "");
        if (smtpFrom.isBlank() && smtpUser.contains("@")) {
            smtpFrom = smtpUser;
        }
        boolean smtpSsl = cfg.getBoolean("smtp.enable_ssl", false);
        if (smtpPort == 465 && !smtpSsl) {
            warnings.add("smtp.port=465 但 enable_ssl=false；将按 465 的惯例自动使用隐式 SSL。");
        }
        if (smtpPort != 465 && smtpSsl) {
            warnings.add("smtp.enable_ssl=true 且端口不是 465；当前实现仍使用隐式 SSL，请确认服务商支持。");
        }
        int smtpTimeout = clamp(cfg.getInt("smtp.connection-timeout-ms", 10_000), 1_000, 60_000,
                "smtp.connection-timeout-ms", warnings);

        boolean emailEnable = cfg.getBoolean("email-verify.enable", true);
        boolean requireBinding = cfg.getBoolean("email-verify.require-binding", true);
        int codeTtl = clamp(cfg.getInt("email-verify.code-ttl-seconds", 600), 60, 3_600,
                "email-verify.code-ttl-seconds", warnings);
        int ticketTtl = clamp(cfg.getInt("email-verify.ticket-ttl-seconds", 1_800), 60, 86_400,
                "email-verify.ticket-ttl-seconds", warnings);
        int maxAttempts = clamp(cfg.getInt("email-verify.max-attempts", 5), 1, 20,
                "email-verify.max-attempts", warnings);
        int cooldown = clamp(cfg.getInt("email-verify.request-cooldown-seconds", 60), 0, 3_600,
                "email-verify.request-cooldown-seconds", warnings);
        boolean requireQqEmail = cfg.getBoolean("email-verify.require-qq-email", true);
        Set<String> domains = new LinkedHashSet<>();
        for (String domain : stringList(cfg, "email-verify.allowed-email-domains")) {
            String cleaned = domain.trim().toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty() && cleaned.matches("[a-z0-9.-]+\\.[a-z]{2,}")) {
                domains.add(cleaned);
            } else {
                warnings.add("忽略非法邮箱域名白名单项: " + domain);
            }
        }
        if (domains.isEmpty()) {
            domains.addAll(List.of("qq.com", "vip.qq.com", "foxmail.com"));
        }

        boolean statsEnable = cfg.getBoolean("stats.enable", true);
        int retention = clamp(cfg.getInt("stats.retention-days", 30), 1, 3_650,
                "stats.retention-days", warnings);
        int reviewAllowHours = clamp(cfg.getInt("review.approval-ttl-hours", 168), 0, 87_600,
                "review.approval-ttl-hours", warnings);

        boolean bStatsEnable = cfg.getBoolean("bstats.enable", true);
        int bStatsId = clamp(cfg.getInt("bstats.plugin-id", 0), 0, 1_000_000,
                "bstats.plugin-id", warnings);
        if (bStatsEnable && bStatsId <= 0) {
            warnings.add("bstats.plugin-id 未配置（0）；bStats 匿名统计保持关闭。请先在 bStats 注册插件并填入 id。");
        }

        boolean updateEnable = cfg.getBoolean("update-check.enable", true);
        String updateEndpoint = string(cfg, "update-check.endpoint",
                "https://api.github.com/repos/chaihuu/CarbonGuard/releases/latest").trim();
        int updateInterval = clamp(cfg.getInt("update-check.interval-minutes", 360), 5, 10_080,
                "update-check.interval-minutes", warnings);
        boolean updateDownload = cfg.getBoolean("update-check.download", true);
        int updateTimeout = clamp(cfg.getInt("update-check.timeout-seconds", 20), 5, 120,
                "update-check.timeout-seconds", warnings);
        if (updateEnable && !validHttpsUrl(updateEndpoint)) {
            warnings.add("update-check.endpoint 必须是合法的 HTTPS URL；自动更新已关闭。");
            updateEnable = false;
        }

        boolean whitelistEnable = cfg.getBoolean("whitelist.enable", true);
        List<String> whitelistIps = stringList(cfg, "whitelist.ips");
        List<String> whitelistPlayers = stringList(cfg, "whitelist.players");
        validateCidrs(whitelistIps, "whitelist.ips", warnings);

        if (webEnable && !webKeyStrong(adminKey)) {
            warnings.add("web.admin-key 未设置或强度不足（至少 24 字符、10 个不同字符）；Web 面板和入服拦截将保持关闭，直到修复。");
        }
        if (smtpEnable && (smtpHost.isBlank() || smtpFrom.isBlank())) {
            warnings.add("SMTP 已启用但 host/from 未配置完整；邮件验证不可用，入服拦截会自动降级关闭。");
        }
        if (smtpEnable && !smtpUser.isBlank() && smtpPassword.isBlank()) {
            warnings.add("SMTP username 已配置但 password/授权码为空；邮件验证不可用，入服拦截会自动降级关闭。");
        }
        CarbonGuardConfig.Web webConfig = new CarbonGuardConfig.Web(webEnable, bindAddress, webPort,
                publicUrl, adminKey.trim(), webRate, publicRate, trustXff, adminKeyGenerated);
        if (requireBinding && webEnable && webConfig.hasUsableAdminKey()
                && !webConfig.hasReachablePublicUrl()) {
            warnings.add("web.public-url 为空或指向回环/通配地址；远程未绑定玩家无法打开绑定链接，"
                    + "入服拦截将保持关闭。请填写公网 HTTPS 地址。");
        }

        if (emailEnable && requireBinding) {
            // Admission gating is enabled by default; make the fail-safe state
            // explicit at load time instead of leaving it to the enable log only.
            validateBindingGateReadiness(webConfig, smtpEnable, smtpHost, smtpFrom, smtpUser,
                    smtpPassword, warnings);
        }

        CarbonGuardConfig config = new CarbonGuardConfig(
                version,
                debug,
                new CarbonGuardConfig.Storage(dbPath,
                        new CarbonGuardConfig.Pool(loginPool, webPool, poolTimeout, cacheRefresh)),
                new CarbonGuardConfig.IpLimit(ipLimitEnable, maxAccounts, grace, mode,
                        List.copyOf(ipLimitIps), List.copyOf(ipLimitPlayers)),
                new CarbonGuardConfig.Ban(banEnable,
                        new CarbonGuardConfig.Ban.IdUidXuid(idBan),
                        new CarbonGuardConfig.Ban.IpAssociation(assocEnable, assocAction, ttlHours)),
                webConfig,
                new CarbonGuardConfig.Smtp(smtpEnable, smtpHost, smtpPort, smtpUser, smtpPassword,
                        smtpFrom.trim(), smtpSsl, smtpTimeout),
                new CarbonGuardConfig.EmailVerify(emailEnable, requireBinding, codeTtl, ticketTtl,
                        maxAttempts, cooldown, requireQqEmail, List.copyOf(domains)),
                new CarbonGuardConfig.Stats(statsEnable, retention),
                new CarbonGuardConfig.Review(reviewAllowHours),
                new CarbonGuardConfig.BStats(bStatsEnable, bStatsId),
                new CarbonGuardConfig.UpdateCheck(updateEnable, updateEndpoint, updateInterval,
                        updateDownload, updateTimeout),
                new CarbonGuardConfig.Whitelist(whitelistEnable, List.copyOf(whitelistIps),
                        List.copyOf(whitelistPlayers))
        );
        return new LoadResult(config, List.copyOf(warnings));
    }

    private static boolean webKeyStrong(String key) {
        return CarbonGuardConfig.Web.isAdminKeyStrong(key);
    }

    /**
     * Checks everything except the live SMTP probe (which only the plugin can run).
     * Missing pieces are reported as a warning so a fresh install makes the
     * fail-safe state obvious in the log and in {@code /carbonguard status}.
     */
    private static void validateBindingGateReadiness(CarbonGuardConfig.Web web, boolean smtpEnable,
                                                     String smtpHost, String smtpFrom, String smtpUser,
                                                     String smtpPassword, List<String> warnings) {
        if (!smtpEnable || smtpHost.isBlank() || smtpFrom.isBlank()
                || (!smtpUser.isBlank() && smtpPassword.isBlank())) {
            warnings.add("入服拦截（email-verify.require-binding）默认开启，但 SMTP 配置不完整；"
                    + "启动时若 SMTP 健康检查失败，拦截会自动降级关闭。");
        }
        if (!web.enable()) {
            warnings.add("入服拦截默认开启，但 web.enable=false；绑定链接无法打开，拦截会自动降级关闭。");
        } else if (!web.hasUsableAdminKey()) {
            warnings.add("入服拦截默认开启，但 web.admin-key 强度不足；Web 面板不可用，拦截会自动降级关闭。");
        } else if (!web.hasReachablePublicUrl()) {
            warnings.add("入服拦截默认开启，但 web.public-url 指向回环/通配地址；"
                    + "远程玩家无法打开绑定链接，拦截会自动降级关闭。");
        }
    }

    private static void validateCidrs(List<String> entries, String path, List<String> warnings) {
        for (String entry : entries) {
            if (CidrMatcher.parse(entry) == null) {
                warnings.add(path + " 中存在非法 IP/CIDR，已忽略: " + entry);
            }
        }
    }

    private static boolean validHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean validHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String expandSecret(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("${ENV:") && trimmed.endsWith("}")) {
            String env = trimmed.substring("${ENV:".length(), trimmed.length() - 1);
            String resolved = System.getenv(env);
            return resolved == null ? "" : resolved;
        }
        return value;
    }

    private static boolean isEnvReference(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("${ENV:") && trimmed.endsWith("}");
    }

    /**
     * Generates a strong random admin key (64 URL-safe characters from 48 random
     * bytes) and best-effort persists it to {@code config.yml} so it survives
     * reloads. Never throws: an unreadable config must not block startup.
     */
    public static void persistAdminKey(Path configFile, String adminKey) throws IOException {
        YamlConfiguration persisted = new YamlConfiguration();
        if (Files.exists(configFile)) {
            try {
                persisted.load(configFile.toFile());
            } catch (InvalidConfigurationException e) {
                throw new IOException("配置文件无法解析，未写入管理密钥: " + e.getMessage(), e);
            }
        }
        persisted.set("web.admin-key", adminKey);
        persisted.save(configFile.toFile());
    }

    private static String autoGenerateAdminKey(Path configFile, List<String> warnings) {
        String generated = Tokens.random(48);
        if (configFile == null) {
            warnings.add("web.admin-key 为空，已自动生成随机管理密钥（仅本次运行有效；"
                    + "未提供配置文件路径）。");
            return generated;
        }
        try {
            persistAdminKey(configFile, generated);
            warnings.add("web.admin-key 为空，已自动生成随机管理密钥并写入 "
                    + configFile.getFileName() + "；启动日志会打印该密钥一次，请立即保存并以最小权限保护文件。");
        } catch (RuntimeException | IOException e) {
            warnings.add("web.admin-key 为空，已自动生成随机管理密钥，但无法写入 "
                    + configFile.getFileName() + "（" + e.getMessage()
                    + "）；本次运行内置该密钥，重启后会重新生成。");
        }
        return generated;
    }

    private static String string(FileConfiguration cfg, String path, String fallback) {
        String value = cfg.getString(path, fallback);
        return value == null ? fallback : value;
    }

    private static List<String> stringList(FileConfiguration cfg, String path) {
        List<String> result = new ArrayList<>();
        for (Object raw : cfg.getList(path, List.of())) {
            if (raw != null) {
                String value = raw.toString().trim();
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static int clamp(int value, int min, int max, String path, List<String> warnings) {
        if (value < min || value > max) {
            int fixed = Math.max(min, Math.min(max, value));
            warnings.add(path + "=" + value + " 超出范围 [" + min + ", " + max + "]，已使用 " + fixed + "。");
            return fixed;
        }
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback,
                                                    String path, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warnings.add(path + "=" + raw + " 非法，已使用 " + fallback + "。");
            return fallback;
        }
    }

    public record LoadResult(CarbonGuardConfig config, List<String> warnings) {
    }
}
