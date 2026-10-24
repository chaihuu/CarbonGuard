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

package xyz.chaihu.carbonguard.update;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.update.UpdateRelease.ReleaseInfo;

/**
 * GitHub-release based update checker/downloader.
 *
 * <p>All network I/O runs on the caller's thread, which the plugin schedules
 * asynchronously. The downloaded jar is written atomically into Paper's
 * {@code plugins/update} directory and is applied on the next restart.</p>
 */
public final class UpdateService {
    private static final int MAX_RELEASE_JSON_BYTES = 1 << 20;
    private static final long MAX_JAR_BYTES = 100L * 1024 * 1024;
    private final JavaPlugin plugin;
    private final Supplier<CarbonGuardConfig> configSupplier;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String lastStatus = "尚未检查";
    private volatile String latestVersion;
    private volatile long lastCheckAt;

    public UpdateService(JavaPlugin plugin, Supplier<CarbonGuardConfig> configSupplier, Logger logger) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.logger = logger;
    }

    /** Runs one check. Must be invoked off the server main thread. */
    public void checkNow() {
        CarbonGuardConfig config = configSupplier.get();
        if (config == null || !config.updateCheck().enable()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        lastCheckAt = System.currentTimeMillis();
        try {
            URI endpoint = URI.create(config.updateCheck().endpoint());
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(config.updateCheck().timeoutSeconds()))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(config.updateCheck().timeoutSeconds()))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CarbonGuard/" + currentVersion())
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                status("更新检查失败: HTTP " + response.statusCode());
                return;
            }
            if (response.body().length() > MAX_RELEASE_JSON_BYTES) {
                status("更新检查失败: 响应过大");
                return;
            }
            ReleaseInfo release = UpdateRelease.parseRelease(response.body());
            if (release == null) {
                status("更新检查失败: 无法解析 release 信息");
                return;
            }
            latestVersion = release.version();
            int comparison = UpdateRelease.compareVersions(release.version(), currentVersion());
            if (comparison <= 0) {
                status("已是最新版本 " + currentVersion());
                return;
            }
            logger.info("发现 CarbonGuard 新版本 " + release.version()
                    + "（当前 " + currentVersion() + "），发布页: " + release.pageUrl());
            if (!config.updateCheck().download()) {
                status("发现新版本 " + release.version() + "，自动下载已关闭");
                return;
            }
            if (release.downloadUrl() == null || release.downloadUrl().isBlank()) {
                status("发现新版本 " + release.version() + "，但 release 没有 jar 资产");
                return;
            }
            download(client, config, release);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status("更新检查被中断");
        } catch (Throwable error) {
            status("更新检查失败: " + error.getClass().getSimpleName());
            logger.log(Level.WARNING, "自动更新检查失败", error);
        } finally {
            running.set(false);
        }
    }

    private void download(HttpClient client, CarbonGuardConfig config, ReleaseInfo release) {
        Path target = null;
        Path temp = null;
        try {
            if (!release.downloadUrl().toLowerCase(Locale.ROOT).startsWith("https://")) {
                status("拒绝下载非 HTTPS 更新资产");
                return;
            }
            Path folder = plugin.getServer().getUpdateFolderFile().toPath();
            Files.createDirectories(folder);
            String fileName = safeFileName(release.assetName(), release.version());
            target = folder.resolve(fileName);
            temp = Files.createTempFile(folder, ".carbon-", ".part");
            HttpRequest request = HttpRequest.newBuilder(URI.create(release.downloadUrl()))
                    .timeout(Duration.ofSeconds(config.updateCheck().timeoutSeconds()))
                    .header("User-Agent", "CarbonGuard/" + currentVersion())
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            long total = 0L;
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) {
                    status("更新下载失败: HTTP " + response.statusCode());
                    return;
                }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (declared > MAX_JAR_BYTES) {
                    status("更新下载失败: 文件过大");
                    return;
                }
                try (java.io.OutputStream output = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        total += read;
                        if (total > MAX_JAR_BYTES) {
                            status("更新下载失败: 文件过大");
                            return;
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
            if (total < 4 || !looksLikeJar(temp)) {
                status("更新下载失败: 下载内容不是有效 jar");
                return;
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            status("已下载 " + release.version() + " 到 " + target.getFileName() + "，重启服务器后生效");
            logger.info("自动更新已下载: " + target + "（重启服务器后由 Paper 更新机制应用）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status("更新下载被中断");
        } catch (Throwable error) {
            status("更新下载失败: " + error.getClass().getSimpleName());
            logger.log(Level.WARNING, "自动更新下载失败", error);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // Best-effort cleanup.
                }
            }
        }
    }

    private static boolean looksLikeJar(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(2);
            return header.length == 2 && header[0] == 'P' && header[1] == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeFileName(String assetName, String version) {
        String name = assetName == null || assetName.isBlank()
                ? "CarbonGuard-" + version + ".jar" : assetName;
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 128) {
            name = name.substring(name.length() - 128);
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = "CarbonGuard-" + version + ".jar";
        }
        return name;
    }

    public String status() {
        long at = lastCheckAt;
        String suffix = at <= 0 ? "" : "（" + java.time.Instant.ofEpochMilli(at)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "）";
        return lastStatus + suffix;
    }

    public String currentVersion() {
        String version = plugin.getPluginMeta().getVersion();
        return version == null || version.isBlank() ? "0" : version;
    }

    public String latestVersion() {
        return latestVersion;
    }

    private void status(String value) {
        lastStatus = value;
    }
}
