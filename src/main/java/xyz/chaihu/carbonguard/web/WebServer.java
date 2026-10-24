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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import xyz.chaihu.carbonguard.CarbonGuardPlugin;

/**
 * Embedded JDK HTTP server. No frontend toolchain and no framework: only the JSON API
 * and four static files are exposed. Bind address defaults to loopback.
 */
public final class WebServer {
    private static final Map<String, Asset> ASSETS = new HashMap<>();
    private final CarbonGuardPlugin plugin;
    private final ApiHandler apiHandler;
    private volatile HttpServer server;
    private volatile ExecutorService executor;

    static {
        ASSETS.put("/", new Asset("web/index.html", "text/html; charset=utf-8", "index.html"));
        ASSETS.put("/index.html", new Asset("web/index.html", "text/html; charset=utf-8", "index.html"));
        ASSETS.put("/static/app.js", new Asset("web/app.js", "text/javascript; charset=utf-8", "app.js"));
        ASSETS.put("/static/style.css", new Asset("web/style.css", "text/css; charset=utf-8", "style.css"));
        ASSETS.put("/favicon.svg", new Asset("web/favicon.svg", "image/svg+xml", "favicon.svg"));
    }

    public WebServer(CarbonGuardPlugin plugin) {
        this.plugin = plugin;
        this.apiHandler = new ApiHandler(plugin);
    }

    public boolean start() {
        if (server != null) {
            return true;
        }
        try {
            Map<String, byte[]> loaded = new HashMap<>();
            for (Map.Entry<String, Asset> entry : ASSETS.entrySet()) {
                byte[] bytes = readResource(entry.getValue().resource());
                if (bytes == null) {
                    plugin.getLogger().severe("Web 静态资源缺失: " + entry.getValue().resource());
                    return false;
                }
                loaded.put(entry.getKey(), bytes);
            }
            InetAddress address = InetAddress.getByName(plugin.config().web().bindAddress());
            server = HttpServer.create(new InetSocketAddress(address, plugin.config().web().port()), 32);
            executor = Executors.newFixedThreadPool(Math.max(4,
                    plugin.config().storage().pool().webMaximumSize() * 2), threadFactory());
            apiHandlerStart(loaded);
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Web 面板已启动: " + plugin.config().web().publicUrl()
                    + " (监听 " + plugin.config().web().bindAddress() + ":" + plugin.config().web().port() + ")");
            return true;
        } catch (Throwable error) {
            plugin.getLogger().log(Level.SEVERE, "Web 面板启动失败", error);
            stop();
            return false;
        }
    }

    private void apiHandlerStart(Map<String, byte[]> assets) {
        server.createContext("/api/", apiHandler);
        server.createContext("/", exchange -> handleStatic(exchange, assets));
    }

    private void handleStatic(HttpExchange exchange, Map<String, byte[]> assets) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpSupport.methodNotAllowed(exchange);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        byte[] bytes = assets.get(path);
        if (bytes == null) {
            HttpSupport.error(exchange, 404, "NOT_FOUND", "页面不存在。");
            return;
        }
        Asset asset = ASSETS.get(path);
        HttpSupport.securityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", asset.contentType());
        exchange.getResponseHeaders().set("Cache-Control",
                path.equals("/") || path.equals("/index.html") ? "no-store" : "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private byte[] readResource(String name) {
        try (InputStream input = plugin.getResource(name)) {
            return input == null ? null : input.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            executor = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public void cleanupRateLimits() {
        apiHandler.cleanupRateLimits();
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "carbonguard-http-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(WebServer.class.getClassLoader());
            return thread;
        };
    }

    private record Asset(String resource, String contentType, String name) {
    }
}
