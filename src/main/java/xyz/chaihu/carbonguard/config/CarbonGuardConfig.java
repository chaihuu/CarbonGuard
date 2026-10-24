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

import java.nio.file.Path;
import java.net.URI;
import java.util.List;
import xyz.chaihu.carbonguard.util.IpUtil;

/**
 * Immutable, validated plugin configuration.
 *
 * <p>The records deliberately expose only plain values. Services receive a snapshot;
 * reloading replaces the snapshot atomically, so a login handler always sees one
 * coherent configuration.</p>
 */
public record CarbonGuardConfig(
        int configVersion,
        boolean debug,
        Storage storage,
        IpLimit ipLimit,
        Ban ban,
        Web web,
        Smtp smtp,
        EmailVerify emailVerify,
        Stats stats,
        Review review,
        BStats bstats,
        UpdateCheck updateCheck,
        Whitelist whitelist
) {
    public record Storage(Path sqliteFile, Pool pool) {
    }

    public record Pool(int loginMaximumSize, int webMaximumSize, int connectionTimeoutMillis, int cacheRefreshSeconds) {
    }

    public record IpLimit(
            boolean enable,
            int maxAccountsPerIp,
            int graceThreshold,
            IpLimitMode mode,
            List<String> whitelistIps,
            List<String> whitelistPlayers
    ) {
        public int hardLimit() {
            return maxAccountsPerIp + graceThreshold;
        }
    }

    public record Ban(boolean enable, IdUidXuid idUidXuid, IpAssociation ipAssociation) {
        public record IdUidXuid(boolean enable) {
        }

        public record IpAssociation(boolean enable, IpAssociationAction action, int ttlHours) {
        }
    }

    public record Web(
            boolean enable,
            String bindAddress,
            int port,
            String publicUrl,
            String adminKey,
            int rateLimitPerMinute,
            int publicRateLimitPerMinute,
            boolean trustForwardedFor
    ) {
        public boolean hasUsableAdminKey() {
            return isAdminKeyStrong(adminKey);
        }

        /**
         * A loopback public URL is unreachable for remote players. Treat it as
         * unavailable whenever the binding gate is being enforced.
         */
        public boolean hasReachablePublicUrl() {
            if (publicUrl == null || publicUrl.isBlank()) {
                return false;
            }
            try {
                URI uri = URI.create(publicUrl);
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    return false;
                }
                if (host.equalsIgnoreCase("localhost")) {
                    return false;
                }
                if (!IpUtil.isLiteral(host)) {
                    return true;
                }
                String normalized = IpUtil.normalize(host);
                return !IpUtil.isLoopback(normalized) && !"0.0.0.0".equals(normalized)
                        && !"::".equals(normalized);
            } catch (RuntimeException e) {
                return false;
            }
        }

        public static boolean isAdminKeyStrong(String value) {
            if (value == null) {
                return false;
            }
            String key = value.trim();
            if (key.length() < 24 || key.length() > 512 || key.chars().distinct().count() < 10) {
                return false;
            }
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            return !lower.contains("password") && !lower.contains("change-me") && !lower.contains("changeme");
        }
    }

    public record Smtp(
            boolean enable,
            String host,
            int port,
            String username,
            String password,
            String from,
            boolean enableSsl,
            int connectionTimeoutMillis
    ) {
        public boolean useImplicitTls() {
            return enableSsl || port == 465;
        }

        public boolean configured() {
            boolean credentials = username == null || username.isBlank()
                    || (password != null && !password.isBlank());
            return enable
                    && host != null && !host.isBlank()
                    && port > 0 && port <= 65_535
                    && from != null && from.contains("@")
                    && (!from.contains("\n") && !from.contains("\r"))
                    && (!host.contains("\n") && !host.contains("\r") && !host.contains(" "))
                    && credentials;
        }
    }

    public record EmailVerify(
            boolean enable,
            boolean requireBinding,
            int codeTtlSeconds,
            int ticketTtlSeconds,
            int maxAttempts,
            int requestCooldownSeconds,
            boolean requireQqEmail,
            List<String> allowedEmailDomains
    ) {
        public boolean emailAllowed(String email) {
            if (email == null || !email.contains("@")) {
                return false;
            }
            String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase();
            if (!requireQqEmail) {
                return true;
            }
            for (String allowed : allowedEmailDomains) {
                if (domain.equals(allowed.toLowerCase())) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Stats(boolean enable, int retentionDays) {
    }

    /**
     * Review workflow tuning. Approved reviews suppress duplicate reviews for the
     * same (type, identity, ip) tuple for this many hours; 0 disables suppression.
     */
    public record Review(int approvalTtlHours) {
    }

    /** bStats is opt-in per plugin id; 0 disables the anonymous metrics client. */
    public record BStats(boolean enable, int pluginId) {
    }

    /** Automatic update check/download. Downloads must stay off the server main thread. */
    public record UpdateCheck(boolean enable, String endpoint, int intervalMinutes,
                              boolean download, int timeoutSeconds) {
    }

    public record Whitelist(boolean enable, List<String> ips, List<String> players) {
    }
}
