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

package xyz.chaihu.carbonguard.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import xyz.chaihu.carbonguard.CarbonGuardPlugin;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.model.BanView;
import xyz.chaihu.carbonguard.data.model.BindingView;
import xyz.chaihu.carbonguard.data.model.ReviewRecord;
import xyz.chaihu.carbonguard.data.model.ReviewStatus;
import xyz.chaihu.carbonguard.data.model.StatsSnapshot;
import xyz.chaihu.carbonguard.service.AuthException;
import xyz.chaihu.carbonguard.service.BindTicketService;
import xyz.chaihu.carbonguard.util.IpUtil;
import xyz.chaihu.carbonguard.util.Tokens;

/**
 * JSON API. Management endpoints require {@code web.admin-key} and are rate limited
 * before authentication so key enumeration is expensive. Only the ticket-bound public
 * verification endpoints are unauthenticated.
 */
public final class ApiHandler implements HttpHandler {
    private static final Pattern REVIEW_PATH = Pattern.compile("^/api/admin/reviews/(\\d+)$");
    private static final Pattern UNBAN_PATH = Pattern.compile("^/api/admin/bans/(\\d+)/unban$");
    private final CarbonGuardPlugin plugin;
    private final RateLimiter adminLimiter = new RateLimiter();
    private final RateLimiter publicLimiter = new RateLimiter();

    public ApiHandler(CarbonGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void cleanupRateLimits() {
        adminLimiter.cleanup();
        publicLimiter.cleanup();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.startsWith("/api/public/")) {
                handlePublic(exchange, path);
            } else if (path.startsWith("/api/admin/")) {
                handleAdmin(exchange, path);
            } else {
                HttpSupport.error(exchange, 404, "NOT_FOUND", "接口不存在。");
            }
        } catch (HttpSupport.BadRequest bad) {
            HttpSupport.error(exchange, 400, "BAD_REQUEST", bad.getMessage());
        } catch (Throwable error) {
            if (!(error instanceof AuthException)) {
                plugin.getLogger().log(Level.WARNING, "Web API 未处理异常 " + path, error);
            }
            HttpSupport.error(exchange, 500, "INTERNAL", "服务器内部错误。");
        }
    }

    private void handlePublic(HttpExchange exchange, String path) {
        CarbonGuardConfig config = plugin.config();
        String ip = HttpSupport.clientIp(exchange, config.web().trustForwardedFor());
        if (!publicLimiter.allow("public:" + IpUtil.networkKey(ip), config.web().publicRateLimitPerMinute())) {
            HttpSupport.error(exchange, 429, "RATE_LIMITED", "请求过于频繁，请稍后再试。");
            return;
        }
        switch (path) {
            case "/api/public/ticket" -> ticket(exchange);
            case "/api/public/request-code" -> requestCode(exchange);
            case "/api/public/verify" -> verify(exchange);
            default -> HttpSupport.error(exchange, 404, "NOT_FOUND", "接口不存在。");
        }
    }

    private void ticket(HttpExchange exchange) {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        String token = HttpSupport.query(exchange).getOrDefault("token", "");
        BindTicketService.Ticket ticket = plugin.bindTicketService().find(token).orElse(null);
        if (ticket == null) {
            HttpSupport.error(exchange, 410, "INVALID_TICKET", "绑定链接无效或已过期，请重新进入服务器。");
            return;
        }
        CarbonGuardConfig config = plugin.config();
        JsonObject body = new JsonObject();
        body.addProperty("playerName", ticket.identity().playerName());
        body.addProperty("bedrock", ticket.identity().bedrock());
        body.addProperty("identityType", ticket.identity().type().name());
        body.addProperty("expiresAt", ticket.expiresAt());
        body.addProperty("requireQqEmail", config.emailVerify().requireQqEmail());
        JsonArray domains = new JsonArray();
        config.emailVerify().allowedEmailDomains().forEach(domains::add);
        body.add("allowedEmailDomains", domains);
        HttpSupport.ok(exchange, body);
    }

    private void requestCode(HttpExchange exchange) {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        JsonObject request = HttpSupport.readJson(exchange);
        String ticket = HttpSupport.string(request, "ticket");
        String email = HttpSupport.string(request, "email");
        plugin.authService().requestCode(ticket, email).whenComplete((issued, error) -> {
            if (error != null) {
                fail(exchange, error);
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("challengeToken", issued.challengeToken());
            body.addProperty("expiresAt", issued.expiresAt());
            body.addProperty("maskedEmail", issued.maskedEmail());
            body.addProperty("ttlSeconds", issued.ttlSeconds());
            body.addProperty("cooldownSeconds", issued.cooldownSeconds());
            HttpSupport.ok(exchange, body);
        });
    }

    private void verify(HttpExchange exchange) {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        JsonObject request = HttpSupport.readJson(exchange);
        String challenge = HttpSupport.string(request, "challengeToken");
        String code = HttpSupport.string(request, "code");
        String qq = HttpSupport.string(request, "qq");
        plugin.authService().verify(challenge, code, qq).whenComplete((binding, error) -> {
            if (error != null) {
                fail(exchange, error);
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("message", "绑定成功，请重新连接服务器。");
            body.addProperty("qq", binding.qq());
            body.addProperty("verifiedAt", binding.verifiedAt());
            HttpSupport.ok(exchange, body);
        });
    }

    private void handleAdmin(HttpExchange exchange, String path) {
        CarbonGuardConfig config = plugin.config();
        String ip = HttpSupport.clientIp(exchange, config.web().trustForwardedFor());
        if (!adminLimiter.allow("admin:" + IpUtil.networkKey(ip), config.web().rateLimitPerMinute())) {
            HttpSupport.error(exchange, 429, "RATE_LIMITED", "请求过于频繁，请稍后再试。");
            return;
        }
        if (!authenticated(exchange, config.web().adminKey())) {
            HttpSupport.error(exchange, 401, "UNAUTHORIZED", "管理密钥无效。");
            return;
        }
        if (path.equals("/api/admin/stats")) {
            stats(exchange);
            return;
        }
        if (path.equals("/api/admin/bans")) {
            bans(exchange);
            return;
        }
        if (path.equals("/api/admin/reviews")) {
            reviews(exchange);
            return;
        }
        if (path.equals("/api/admin/bindings")) {
            bindings(exchange);
            return;
        }
        if (path.equals("/api/admin/bindings/unbind")) {
            unbindBinding(exchange);
            return;
        }
        Matcher review = REVIEW_PATH.matcher(path);
        if (review.matches()) {
            decideReview(exchange, Long.parseLong(review.group(1)));
            return;
        }
        Matcher unban = UNBAN_PATH.matcher(path);
        if (unban.matches()) {
            unban(exchange, Long.parseLong(unban.group(1)));
            return;
        }
        HttpSupport.error(exchange, 404, "NOT_FOUND", "接口不存在。");
    }

    private boolean authenticated(HttpExchange exchange, String adminKey) {
        if (adminKey == null || adminKey.length() < 24) {
            return false;
        }
        String candidate = exchange.getRequestHeaders().getFirst("X-CarbonGuard-Key");
        if (candidate == null || candidate.isBlank()) {
            candidate = HttpSupport.query(exchange).get("key");
        }
        if (candidate == null || candidate.isBlank()) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                candidate = authorization.substring(7).trim();
            }
        }
        if (candidate == null) {
            return false;
        }
        String trimmed = candidate.trim();
        return trimmed.length() <= 512 && Tokens.constantTimeEquals(trimmed, adminKey);
    }

    private void stats(HttpExchange exchange) {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        plugin.adminService().stats().whenComplete((snapshot, error) -> {
            if (error != null) {
                fail(exchange, error);
                return;
            }
            HttpSupport.ok(exchange, statsJson(snapshot));
        });
    }

    private JsonObject statsJson(StatsSnapshot snapshot) {
        JsonObject body = new JsonObject();
        body.addProperty("identities", snapshot.identities());
        body.addProperty("bindings", snapshot.bindings());
        body.addProperty("activeBans", snapshot.activeBans());
        body.addProperty("pendingReviews", snapshot.pendingReviews());
        body.addProperty("logins24h", snapshot.logins24h());
        body.addProperty("denied24h", snapshot.denied24h());
        body.addProperty("uniqueIps24h", snapshot.uniqueIps24h());
        body.addProperty("online", snapshot.onlineSessions());
        JsonArray series = new JsonArray();
        for (StatsSnapshot.DayCount day : snapshot.loginSeries()) {
            JsonObject item = new JsonObject();
            item.addProperty("day", day.day());
            item.addProperty("allowed", day.allowed());
            item.addProperty("denied", day.denied());
            series.add(item);
        }
        body.add("loginSeries", series);
        CarbonGuardConfig config = plugin.config();
        JsonObject modules = new JsonObject();
        modules.addProperty("ipLimit", config.ipLimit().enable());
        modules.addProperty("ipLimitMode", config.ipLimit().mode().name());
        modules.addProperty("ipLimitHardLimit", config.ipLimit().hardLimit());
        modules.addProperty("ban", config.ban().enable());
        modules.addProperty("ipAssociationAction", config.ban().ipAssociation().action().name());
        modules.addProperty("emailVerify", config.emailVerify().enable());
        modules.addProperty("requireBinding", config.emailVerify().requireBinding());
        modules.addProperty("bindingGateOperational", plugin.bindingGateOperational());
        modules.addProperty("smtp", config.smtp().configured());
        modules.addProperty("smtpOperational", plugin.mailService() != null
                && plugin.mailService().operational());
        modules.addProperty("web", plugin.webServer() != null && plugin.webServer().isRunning());
        modules.addProperty("floodgate", plugin.identityResolver().floodgatePresent());
        modules.addProperty("geyser", plugin.identityResolver().geyserPresent());
        body.add("modules", modules);
        body.addProperty("generatedAt", System.currentTimeMillis());
        return body;
    }

    private void bans(HttpExchange exchange) {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        Map<String, String> query = HttpSupport.query(exchange);
        String q = query.getOrDefault("q", "");
        int limit = intParam(query.get("limit"), 50, 1, 100);
        plugin.adminService().searchBans(q, limit).whenComplete((bans, error) -> {
            if (error != null) {
                fail(exchange, error);
                return;
            }
            JsonObject body = new JsonObject();
            JsonArray array = new JsonArray();
            for (BanView ban : bans) {
                array.add(banJson(ban));
            }
            body.add("bans", array);
            body.addProperty("count", array.size());
            HttpSupport.ok(exchange, body);
        });
    }

    private JsonObject banJson(BanView ban) {
        JsonObject item = new JsonObject();
        item.addProperty("id", ban.id());
        item.addProperty("identity", ban.identityKey());
        item.addProperty("player", ban.playerName());
        item.addProperty("reason", ban.reason());
        item.addProperty("createdBy", ban.createdBy());
        item.addProperty("createdAt", ban.createdAt());
        item.addProperty("expiresAt", ban.expiresAt());
        item.addProperty("active", ban.effectiveActive());
        item.addProperty("liftedAt", ban.liftedAt());
        item.addProperty("liftedBy", ban.liftedBy());
        JsonArray ips = new JsonArray();
        ban.associatedIps().forEach(ips::add);
        item.add("ips", ips);
        return item;
    }

    private void bindings(HttpExchange exchange) {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        Map<String, String> query = HttpSupport.query(exchange);
        String q = query.getOrDefault("q", "");
        int limit = intParam(query.get("limit"), 50, 1, 100);
        plugin.adminService().searchBindings(q, limit).whenComplete((bindings, error) -> {
            if (error != null) {
                fail(exchange, error);
                return;
            }
            JsonObject body = new JsonObject();
            JsonArray array = new JsonArray();
            for (BindingView binding : bindings) {
                array.add(bindingJson(binding));
            }
            body.add("bindings", array);
            body.addProperty("count", array.size());
            HttpSupport.ok(exchange, body);
        });
    }

    private JsonObject bindingJson(BindingView binding) {
        JsonObject item = new JsonObject();
        item.addProperty("identity", binding.identityKey());
        item.addProperty("player", binding.playerName());
        item.addProperty("email", binding.email());
        item.addProperty("qq", binding.qq());
        item.addProperty("verifiedAt", binding.verifiedAt());
        item.addProperty("lastSeen", binding.lastSeen());
        return item;
    }

    private void unbindBinding(HttpExchange exchange) {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        JsonObject request = HttpSupport.readJson(exchange);
        String identity = HttpSupport.string(request, "identity");
        if (identity.isBlank()) {
            HttpSupport.error(exchange, 400, "BAD_REQUEST", "identity 不能为空");
            return;
        }
        String actor = "web:" + HttpSupport.clientIp(exchange, plugin.config().web().trustForwardedFor());
        plugin.adminService().unbind(identity, actor).whenComplete((removed, error) -> {
            if (error != null) {
                fail(exchange, error);
                return;
            }
            if (!Boolean.TRUE.equals(removed)) {
                HttpSupport.error(exchange, 404, "NOT_FOUND", "绑定记录不存在。");
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("message", "已解除该身份的游戏绑定。");
            HttpSupport.ok(exchange, body);
        });
    }

    private void reviews(HttpExchange exchange) {        if (!requireMethod(exchange, "GET")) {
            return;
        }
        Map<String, String> query = HttpSupport.query(exchange);
        String rawStatus = query.getOrDefault("status", "PENDING").trim().toUpperCase(Locale.ROOT);
        ReviewStatus status;
        try {
            status = rawStatus.equals("ALL") ? null : ReviewStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException e) {
            HttpSupport.error(exchange, 400, "BAD_REQUEST", "status 必须是 PENDING、APPROVED、REJECTED 或 ALL。");
            return;
        }
        int limit = intParam(query.get("limit"), 100, 1, 200);
        plugin.reviewService().list(status, limit).whenComplete((reviews, error) -> {
            if (error != null) {
                fail(exchange, error);
                return;
            }
            JsonObject body = new JsonObject();
            JsonArray array = new JsonArray();
            for (ReviewRecord review : reviews) {
                array.add(reviewJson(review));
            }
            body.add("reviews", array);
            body.addProperty("count", array.size());
            HttpSupport.ok(exchange, body);
        });
    }

    private JsonObject reviewJson(ReviewRecord review) {
        JsonObject item = new JsonObject();
        item.addProperty("id", review.id());
        item.addProperty("type", review.type().name());
        item.addProperty("identity", review.identityKey());
        item.addProperty("player", review.playerName());
        item.addProperty("ip", review.ip());
        item.addProperty("reason", review.reason());
        item.addProperty("status", review.status().name());
        item.addProperty("createdAt", review.createdAt());
        item.addProperty("banId", review.banId());
        return item;
    }

    private void decideReview(HttpExchange exchange, long id) {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        JsonObject request = HttpSupport.readJson(exchange);
        String raw = HttpSupport.string(request, "action").toUpperCase(Locale.ROOT);
        ReviewStatus status = switch (raw) {
            case "APPROVE", "APPROVED" -> ReviewStatus.APPROVED;
            case "REJECT", "REJECTED" -> ReviewStatus.REJECTED;
            default -> null;
        };
        if (status == null) {
            HttpSupport.error(exchange, 400, "BAD_REQUEST", "action 必须是 APPROVE 或 REJECT。");
            return;
        }
        String actor = "web:" + HttpSupport.clientIp(exchange, plugin.config().web().trustForwardedFor());
        plugin.reviewService().decide(id, status, actor).whenComplete((review, error) -> {
            if (error != null) {
                fail(exchange, error);
                return;
            }
            if (review.status() == ReviewStatus.REJECTED) {
                plugin.kickOnlineIdentity(review.identityKey(), review.playerName(),
                        plugin.messages().component("kick.review-rejected"));
            }
            JsonObject body = new JsonObject();
            body.add("review", reviewJson(review));
            HttpSupport.ok(exchange, body);
        });
    }

    private void unban(HttpExchange exchange, long banId) {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        String actor = "web:" + HttpSupport.clientIp(exchange, plugin.config().web().trustForwardedFor());
        plugin.banService().unban(banId, actor).whenComplete((changed, error) -> {
            if (error != null) {
                fail(exchange, error);
                return;
            }
            if (!Boolean.TRUE.equals(changed)) {
                HttpSupport.error(exchange, 404, "NOT_FOUND", "封禁记录不存在或已解封。");
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("message", "已解封并清除该封禁的 IP 关联。");
            HttpSupport.ok(exchange, body);
        });
    }

    private void fail(HttpExchange exchange, Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof AuthException auth) {
            HttpSupport.error(exchange, auth.error().httpStatus(), auth.error().name(), auth.getMessage());
            return;
        }
        if (cause instanceof HttpSupport.BadRequest bad) {
            HttpSupport.error(exchange, 400, "BAD_REQUEST", bad.getMessage());
            return;
        }
        if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
            HttpSupport.error(exchange, 409, "CONFLICT", cause.getMessage());
            return;
        }
        plugin.getLogger().log(Level.WARNING, "Web API 异步处理失败", cause);
        HttpSupport.error(exchange, 500, "INTERNAL", "服务器内部错误。");
    }

    private static boolean requireMethod(HttpExchange exchange, String method) {
        if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
            HttpSupport.methodNotAllowed(exchange);
            return false;
        }
        return true;
    }

    private static int intParam(String value, int fallback, int min, int max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
