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
import java.util.List;
import java.util.Locale;
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

    /**
     * Loads an on-disk config. A {@code config.yml} written by an older release is
     * migrated, rewritten on disk (with a timestamped backup) and then loaded, so the
     * new keys and the current {@code config-version} become visible to the operator.
     *
     * <p>Migration never overwrites operator values: only removed options are dropped,
     * missing ones are filled with defaults, and comments survive wherever
     * YamlConfiguration can preserve them.</p>
     */
    public static LoadResult loadFile(Path configFile, Path dataFolder)
            throws IOException, InvalidConfigurationException {
        if (!Files.exists(configFile)) {
            throw new IOException("配置文件不存在: " + configFile);
        }
        List<String> warnings = new ArrayList<>();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(configFile.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            warnings.add("config.yml 无法解析（" + e.getMessage() + "），本次按默认配置运行；"
                    + "请检查该文件后再执行 /carbonguard reload。");
        }
        // Persist order matters: migration and a generated admin key are written once,
        // so a later write can never drop the keys an earlier one just added.
        boolean migrated = migrate(yaml);
        String generatedKey = generatedAdminKey(yaml);
        boolean storedGeneratedKey = generatedKey != null;
        if (storedGeneratedKey) {
            // The key now lives at web.admin-key, so load() no longer sees an empty value.
            // Mark it so the UI and startup log still report where the key came from.
            yaml.set("web.admin-key-auto-generated", true);
        } else if (yaml.contains("web.admin-key-auto-generated", true)) {
            yaml.set("web.admin-key-auto-generated", null);
            migrated = true;
        }
        if (migrated || storedGeneratedKey) {
            persistConfigFile(configFile, yaml, generatedKey, migrated, warnings);
        }
        LoadResult result = load(yaml, dataFolder, configFile);
        List<String> merged = new ArrayList<>(warnings);
        merged.addAll(result.warnings());
        return new LoadResult(result.config(), List.copyOf(merged));
    }

    /**
     * Upgrades an older config in place. Returns true when the document changed.
     *
     * <p>v1 to v2: the per-login bind ticket and the free-form e-mail options were
     * replaced by the player-initiated {@code /bind} page. The ticket/e-mail allow-list
     * keys are dropped, {@code binding.*} is added, and {@code config-version} bumps.</p>
     */
    public static boolean migrate(YamlConfiguration yaml) {
        int version = yaml.getInt("config-version", 1);
        if (version > CarbonGuardConfig.CONFIG_VERSION) {
            return false;
        }
        boolean changed = false;
        if (version < 2) {
            changed |= migrateV1ToV2(yaml);
        }
        if (version < CarbonGuardConfig.CONFIG_VERSION) {
            yaml.set("config-version", CarbonGuardConfig.CONFIG_VERSION);
            changed = true;
        }
        return changed;
    }

    private static boolean migrateV1ToV2(YamlConfiguration yaml) {
        boolean changed = false;
        // Options that no longer exist; they only configured the removed ticket flow.
        for (String legacy : List.of("email-verify.ticket-ttl-seconds",
                "email-verify.require-qq-email", "email-verify.allowed-email-domains")) {
            if (yaml.contains(legacy, true)) {
                yaml.set(legacy, null);
                changed = true;
            }
        }
        if (!yaml.contains("binding", true)) {
            yaml.createSection("binding");
        }
        changed |= setIfMissing(yaml, "binding.enable", true);
        changed |= setIfMissing(yaml, "binding.verify-url", "/bind");
        changed |= setIfMissing(yaml, "binding.show-avatars", true);
        return changed;
    }

    private static boolean setIfMissing(YamlConfiguration yaml, String path, Object value) {
        if (yaml.contains(path, true)) {
            return false;
        }
        yaml.set(path, value);
        return true;
    }

    private static String backupName(Path configFile) {
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String name = configFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        String extension = dot <= 0 ? "" : name.substring(dot);
        return base + "." + stamp + extension;
    }

    /**
     * Generates the admin key when section {@code web} asks for one but has none. The
     * key is stored in the in-memory document so it is persisted in one write together
     * with any migration. A {@code ${ENV:...}} reference is never replaced.
     *
     * @return the generated key, or {@code null} when nothing had to be generated
     */
    private static String generatedAdminKey(YamlConfiguration yaml) {
        if (!yaml.getBoolean("web.enable", true)) {
            return null;
        }
        String raw = yaml.getString("web.admin-key", "");
        if (isEnvReference(raw) || (raw != null && !raw.isBlank())) {
            return null;
        }
        String generated = Tokens.random(48);
        yaml.set("web.admin-key", generated);
        return generated;
    }

    /**
     * Writes the config once: backs up the previous file when it was migrated, saves the
     * document (including a freshly generated admin key) and reports what happened.
     */
    private static void persistConfigFile(Path configFile, YamlConfiguration yaml, String generatedKey,
                                          boolean migrated, List<String> warnings) {
        String target = configFile.getFileName().toString();
        try {
            if (migrated) {
                Files.copy(configFile, configFile.resolveSibling(backupName(configFile)));
            }
            yaml.save(configFile.toFile());
        } catch (IOException e) {
            if (migrated) {
                warnings.add("config-version 已在内存中升级到 v" + CarbonGuardConfig.CONFIG_VERSION
                        + "，但写入 " + target + " 失败（" + e.getMessage()
                        + "）；本次运行使用内存中已升级的配置。");
            }
            if (generatedKey != null) {
                warnings.add("web.admin-key 为空，已生成随机管理密钥，但无法写入 " + target
                        + "（" + e.getMessage() + "）；本次运行内置该密钥，重启后会重新生成。"
                        + "启动日志会打印该密钥一次，请立即保存。");
            }
            return;
        }
        if (migrated) {
            warnings.add("config-version 已自动升级到 v" + CarbonGuardConfig.CONFIG_VERSION
                    + "（已更新 " + target + "，旧文件备份为 " + backupName(configFile) + "）。");
        }
        if (generatedKey != null) {
            warnings.add("web.admin-key 为空，已自动生成随机管理密钥并写入 " + target
                    + "；启动日志会打印该密钥一次，请立即保存并以最小权限保护文件。");
        }
    }

    public static LoadResult load(FileConfiguration cfg, Path dataFolder) {
        return load(cfg, dataFolder, null);
    }

    private static LoadResult load(FileConfiguration cfg, Path dataFolder, Path configFile) {
        List<String> warnings = new ArrayList<>();

        int rawVersion = cfg.getInt("config-version", 0);
        int version = clamp(rawVersion <= 0 ? CarbonGuardConfig.CONFIG_VERSION : rawVersion, 1,
                CarbonGuardConfig.CONFIG_VERSION, "config-version", warnings);
        if (rawVersion <= 0) {
            warnings.add("config-version 缺失，按当前版本 v" + CarbonGuardConfig.CONFIG_VERSION + " 处理。");
        } else if (rawVersion < CarbonGuardConfig.CONFIG_VERSION) {
            warnings.add("config-version=" + rawVersion + " 低于当前版本；缺失项已按默认值填充。");
        } else if (rawVersion > CarbonGuardConfig.CONFIG_VERSION) {
            warnings.add("config-version=" + rawVersion + " 高于当前插件版本；请升级插件，避免新配置被忽略。");
        }
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
        boolean exemptBedrock = cfg.getBoolean("ip-limit.exempt-bedrock", true);
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
        int webPort = clamp(cfg.getInt("web.port", 9_981), 1, 65_535, "web.port", warnings);
        String rawAdminKey = string(cfg, "web.admin-key", "");
        String adminKey = expandSecret(rawAdminKey);
        // loadFile records a generated key on disk; a hand-written 64-char key is not
        // reported as generated merely because it happens to match the shape.
        boolean adminKeyGenerated = cfg.getBoolean("web.admin-key-auto-generated", false);
        if (webEnable && adminKey.isBlank()) {
            if (isEnvReference(rawAdminKey)) {
                // The environment variable is missing: use a generated key for this run
                // only. Rewriting the file would destroy the ${ENV:...} intent.
                warnings.add(rawAdminKey + " 对应的环境变量为空；已为本进程生成临时随机管理密钥（不会写入配置文件）。");
            } else {
                warnings.add("web.admin-key 为空，已自动生成随机管理密钥并写入 config.yml；"
                        + "启动日志会打印该密钥一次，请立即保存。");
            }
            // loadFile already stored an in-memory key; only generate when it is absent.
            String stored = cfg.getString("web.admin-key", "");
            adminKey = isGeneratedKey(stored) ? stored : Tokens.random(48);
            adminKeyGenerated = true;
        }
        String publicUrl = string(cfg, "web.public-url", "");
        if (publicUrl.isBlank()) {
            publicUrl = "http://" + IpUtil.displayHost(bindAddress) + ":" + webPort;
            if (bindAddress.equals("0.0.0.0") || bindAddress.equals("::")) {
                warnings.add("web.bind-address 为通配地址但 web.public-url 为空；"
                        + "踢出消息中的链接会指向回环地址，远程玩家无法打开。请显式填写 public-url。");
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
        int maxAttempts = clamp(cfg.getInt("email-verify.max-attempts", 5), 1, 20,
                "email-verify.max-attempts", warnings);
        int cooldown = clamp(cfg.getInt("email-verify.request-cooldown-seconds", 60), 0, 3_600,
                "email-verify.request-cooldown-seconds", warnings);
        // The mailbox is fixed to <ID>@qq.com; the legacy allow-list is no longer read.
        boolean requireQqEmail = true;
        List<String> domains = List.of("qq.com");

        boolean bindingEnable = cfg.getBoolean("binding.enable", true);
        String verifyPath = string(cfg, "binding.verify-url", "/bind").trim();
        if (!verifyPath.startsWith("/") || verifyPath.contains("?") || verifyPath.contains("#")
                || verifyPath.contains(" ") || verifyPath.length() > 128) {
            warnings.add("binding.verify-url 必须是以 / 开头、不含查询/片段的站内路径；已回退到 /bind。");
            verifyPath = "/bind";
        }
        while (verifyPath.length() > 1 && verifyPath.endsWith("/")) {
            verifyPath = verifyPath.substring(0, verifyPath.length() - 1);
        }
        boolean showAvatars = cfg.getBoolean("binding.show-avatars", true);
        if (cfg.contains("binding.require-email-domain", true)
                || cfg.contains("binding.require-email-code", true)) {
            warnings.add("binding.require-email-code / require-email-domain 已取消："
                    + "绑定邮箱固定为 ID@qq.com，这两项可安全删除。");
        }
        if (!bindingEnable) {
            warnings.add("binding.enable=false：玩家无法自助绑定；未完成绑定的玩家将一直被入服拦截拒绝。");
        }
        if (requireBinding && bindingEnable && (smtpHost.isBlank() || smtpFrom.isBlank())) {
            warnings.add("入服拦截已开启，但绑定需要向 <ID>@qq.com 发送验证码；"
                    + "请配置 smtp，否则未绑定玩家无法完成绑定。");
        }

        boolean statsEnable = cfg.getBoolean("stats.enable", true);
        int retention = clamp(cfg.getInt("stats.retention-days", 30), 1, 3_650,
                "stats.retention-days", warnings);
        int reviewAllowHours = clamp(cfg.getInt("review.approval-ttl-hours", 168), 0, 87_600,
                "review.approval-ttl-hours", warnings);
        boolean playersEnable = cfg.getBoolean("players.enable", true);
        int playersRetention = clamp(cfg.getInt("players.retention-days", 0), 0, 36_500,
                "players.retention-days", warnings);

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
            warnings.add("web.admin-key 未设置或强度不足（至少 24 字符、10 个不同字符）；"
                    + "Web 面板和入服拦截将保持关闭，直到修复。");
        }
        if (smtpEnable && (smtpHost.isBlank() || smtpFrom.isBlank())) {
            warnings.add("SMTP 已启用但 host/from 未配置完整；绑定验证码无法发送。");
        }
        if (smtpEnable && !smtpUser.isBlank() && smtpPassword.isBlank()) {
            warnings.add("SMTP username 已配置但 password/授权码为空；绑定验证码无法发送。");
        }
        CarbonGuardConfig.Web webConfig = new CarbonGuardConfig.Web(webEnable, bindAddress, webPort,
                publicUrl, adminKey.trim(), webRate, publicRate, trustXff, adminKeyGenerated);
        CarbonGuardConfig.Binding bindingConfig = new CarbonGuardConfig.Binding(
                bindingEnable, verifyPath, showAvatars);
        if (requireBinding && webEnable && webConfig.hasUsableAdminKey()
                && !webConfig.hasReachablePublicUrl()) {
            warnings.add("web.public-url 为空或指向回环/通配地址；远程未绑定玩家无法打开绑定页面，"
                    + "入服拦截将保持关闭。请填写公网 HTTPS 地址。");
        }

        if (emailEnable && requireBinding) {
            // Admission gating is enabled by default; make the fail-safe state
            // explicit at load time instead of leaving it to the enable log only.
            validateBindingGateReadiness(webConfig, bindingConfig, smtpEnable, smtpHost, smtpFrom,
                    smtpUser, smtpPassword, warnings);
        }

        CarbonGuardConfig config = new CarbonGuardConfig(
                version,
                debug,
                new CarbonGuardConfig.Storage(dbPath,
                        new CarbonGuardConfig.Pool(loginPool, webPool, poolTimeout, cacheRefresh)),
                new CarbonGuardConfig.IpLimit(ipLimitEnable, maxAccounts, grace, mode, exemptBedrock,
                        List.copyOf(ipLimitIps), List.copyOf(ipLimitPlayers)),
                new CarbonGuardConfig.Ban(banEnable,
                        new CarbonGuardConfig.Ban.IdUidXuid(idBan),
                        new CarbonGuardConfig.Ban.IpAssociation(assocEnable, assocAction, ttlHours)),
                webConfig,
                bindingConfig,
                new CarbonGuardConfig.Smtp(smtpEnable, smtpHost, smtpPort, smtpUser, smtpPassword,
                        smtpFrom.trim(), smtpSsl, smtpTimeout),
                new CarbonGuardConfig.EmailVerify(emailEnable, requireBinding, codeTtl,
                        maxAttempts, cooldown, requireQqEmail, List.copyOf(domains)),
                new CarbonGuardConfig.Stats(statsEnable, retention),
                new CarbonGuardConfig.Review(reviewAllowHours),
                new CarbonGuardConfig.BStats(bStatsEnable, bStatsId),
                new CarbonGuardConfig.UpdateCheck(updateEnable, updateEndpoint, updateInterval,
                        updateDownload, updateTimeout),
                new CarbonGuardConfig.Players(playersEnable, playersRetention),
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
    private static void validateBindingGateReadiness(CarbonGuardConfig.Web web,
                                                     CarbonGuardConfig.Binding binding,
                                                     boolean smtpEnable, String smtpHost,
                                                     String smtpFrom, String smtpUser,
                                                     String smtpPassword, List<String> warnings) {
        if (!smtpEnable || smtpHost.isBlank() || smtpFrom.isBlank()
                || (!smtpUser.isBlank() && smtpPassword.isBlank())) {
            warnings.add("绑定流程需要向 <ID>@qq.com 发送验证码，但 SMTP 配置不完整；玩家将无法完成绑定。");
        }
        if (!binding.enable()) {
            warnings.add("入服拦截默认开启，但 binding.enable=false；玩家无法自助绑定。");
        }
        if (!web.enable()) {
            warnings.add("入服拦截默认开启，但 web.enable=false；绑定页面无法打开，拦截会自动降级关闭。");
        } else if (!web.hasUsableAdminKey()) {
            warnings.add("入服拦截默认开启，但 web.admin-key 强度不足；Web 面板不可用，拦截会自动降级关闭。");
        } else if (!web.hasReachablePublicUrl()) {
            warnings.add("入服拦截默认开启，但 web.public-url 指向回环/通配地址；"
                    + "远程玩家无法打开绑定页面，拦截会自动降级关闭。");
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

    /** A URL-safe 64-character key, i.e. {@link Tokens#random(int) Tokens.random(48)}. */
    private static boolean isGeneratedKey(String value) {
        return value != null && value.length() == 64 && value.matches("[A-Za-z0-9_-]{64}");
    }

    /**
     * Persists only the admin key, preserving every other setting and comment. Used by
     * the reload path when a key was generated for an otherwise unchanged config.
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
