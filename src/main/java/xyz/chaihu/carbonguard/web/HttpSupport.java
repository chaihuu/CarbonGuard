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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import xyz.chaihu.carbonguard.util.IpUtil;

/** Small HTTP helpers: bounded JSON bodies, JSON responses and security headers. */
public final class HttpSupport {
    public static final int MAX_BODY_BYTES = 16 * 1024;
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "connect-src 'self'; img-src 'self' data: https://q1.qlogo.cn; font-src 'self'; "
            + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    private HttpSupport() {
    }

    public static void securityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Content-Security-Policy", CSP);
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
    }

    public static JsonObject readJson(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new BadRequest("Content-Type 必须为 application/json");
        }
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new BadRequest("读取请求体失败");
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new BadRequest("请求体过大");
        }
        if (body.length == 0) {
            throw new BadRequest("请求体不能为空");
        }
        try {
            var element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new BadRequest("请求体必须是 JSON 对象");
            }
            return element.getAsJsonObject();
        } catch (BadRequest e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BadRequest("JSON 格式错误");
        }
    }

    public static String string(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        try {
            return object.get(name).getAsString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            try {
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed percent escapes instead of failing the request.
            }
        }
        return result;
    }

    public static String clientIp(HttpExchange exchange, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                if (IpUtil.isLiteral(first)) {
                    return IpUtil.normalize(first);
                }
            }
        }
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null ? "0.0.0.0" : IpUtil.normalize(remote.getAddress());
    }

    public static void json(HttpExchange exchange, int status, JsonObject body) {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        try {
            securityHeaders(exchange);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // Client disconnected; nothing useful to do.
        } finally {
            exchange.close();
        }
    }

    public static void ok(HttpExchange exchange, JsonObject body) {
        body.addProperty("ok", true);
        json(exchange, 200, body);
    }

    public static void error(HttpExchange exchange, int status, String code, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error", code);
        body.addProperty("message", message);
        json(exchange, status, body);
    }

    public static void methodNotAllowed(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Allow", "GET, POST");
        error(exchange, 405, "METHOD_NOT_ALLOWED", "请求方法不允许。");
    }

    public static final class BadRequest extends RuntimeException {
        public BadRequest(String message) {
            super(message);
        }
    }
}
