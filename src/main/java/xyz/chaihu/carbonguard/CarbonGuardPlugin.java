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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import xyz.chaihu.carbonguard.command.CarbonGuardCommand;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.config.ConfigLoader;
import xyz.chaihu.carbonguard.config.Messages;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.identity.IdentityResolver;
import xyz.chaihu.carbonguard.listener.LoginContexts;
import xyz.chaihu.carbonguard.listener.LoginListener;
import xyz.chaihu.carbonguard.listener.SessionListener;
import xyz.chaihu.carbonguard.mail.MailService;
import xyz.chaihu.carbonguard.service.AccessPolicy;
import xyz.chaihu.carbonguard.service.AdminService;
import xyz.chaihu.carbonguard.service.AuthService;
import xyz.chaihu.carbonguard.service.BanService;
import xyz.chaihu.carbonguard.service.BindTicketService;
import xyz.chaihu.carbonguard.service.LoginGate;
import xyz.chaihu.carbonguard.service.ReviewService;
import xyz.chaihu.carbonguard.update.UpdateService;
import xyz.chaihu.carbonguard.util.IpUtil;
import xyz.chaihu.carbonguard.util.TimeUtil;
import xyz.chaihu.carbonguard.web.WebServer;

/**
 * CarbonGuard Paper 26.2 entry point.
 *
 * <p>Startup order is deliberate: database/schema -> cache warm-up -> identity resolver
 * -> login gate -> optional web/SMTP. A failure in the data layer disables the plugin
 * rather than silently enforcing half-loaded rules.</p>
 */
public final class CarbonGuardPlugin extends JavaPlugin {
    private volatile CarbonGuardConfig config;
    private volatile Messages messages;
    private DataStore data;
    private IdentityResolver identityResolver;
    private AccessPolicy accessPolicy;
    private MailService mailService;
    private BindTicketService bindTickets;
    private AuthService authService;
    private LoginGate loginGate;
    private BanService banService;
    private ReviewService reviewService;
    private volatile AdminService adminService;
    private volatile WebServer webServer;
    private volatile UpdateService updateService;
    private volatile Metrics bStats;
    private volatile int bStatsPluginId;
    private LoginContexts loginContexts;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private volatile boolean reloading;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigLoader.LoadResult loaded;
        try {
            loaded = ConfigLoader.loadFile(getDataFolder().toPath().resolve("config.yml"),
                    getDataFolder().toPath());
            this.messages = Messages.load(this);
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "config.yml / messages.yml 解析失败，插件不启动", error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.config = loaded.config();
        for (String warning : loaded.warnings()) {
            getLogger().warning(warning);
        }

        this.identityResolver = new IdentityResolver(getLogger());
        this.data = new DataStore(config, getLogger());
        try {
            data.init(identityResolver.floodgatePresent());
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "数据层初始化失败，插件将被禁用（不启用任何降级绕过）", error);
            data.close();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.accessPolicy = new AccessPolicy(this::config);
        this.mailService = new MailService(this::config, getLogger());
        this.bindTickets = new BindTicketService(data, this::config);
        this.authService = new AuthService(data, bindTickets, mailService, this::config, getLogger());
        this.banService = new BanService(data, this::config);
        this.reviewService = new ReviewService(data, this::config);
        this.adminService = new AdminService(data, config, bindTickets);
        this.updateService = new UpdateService(this, this::config, getLogger());
        this.loginContexts = new LoginContexts();
        this.loginGate = new LoginGate(data, data.sessions(), accessPolicy, bindTickets,
                this::messages, this::config, this::bindingGateOperational, this::bindingGateMaintenance);

        getServer().getPluginManager().registerEvents(new LoginListener(this, loginContexts), this);
        getServer().getPluginManager().registerEvents(new SessionListener(this, loginContexts), this);
        PluginCommand command = getCommand("carbonguard");
        if (command != null) {
            CarbonGuardCommand handler = new CarbonGuardCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        resumeOnlinePlayers();
        startWeb();
        probeMail();
        startBStats();
        if (config.emailVerify().enable() && config.emailVerify().requireBinding()
                && !bindingGateConfigurationComplete()) {
            getLogger().severe("入服拦截已开启，但 SMTP/Web/管理密钥/public-url 不完整；"
                    + "为防止锁死服务器，该拦截已自动降级关闭。修复配置后执行 /carbonguard reload。");
        }
        scheduleTasks();
        getLogger().info("CarbonGuard 已启用 | Paper 26.2 | " + identityResolver.integrationSummary()
                + " | 入服拦截=" + (bindingGateOperational() ? "开启" : "关闭"));
    }

    /**
     * Paper can reload a plugin while players remain online. Rebuild their session
     * leases so the per-IP counter is not silently reset to zero.
     */
    private void resumeOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            try {
                String ip = IpUtil.normalize(
                        player.getAddress() == null ? null : player.getAddress().getAddress());
                var identity = identityResolver.resolve(player.getUniqueId(), player.getName());
                var acquired = data.sessions().tryAcquire(ip, identity.key(), player.getUniqueId(), 0);
                data.sessions().confirm(player.getUniqueId(), acquired.sessionId());
                data.sessions().confirmOrCreate(ip, identity.key(), player.getUniqueId(), acquired.sessionId());
                data.touchIdentity(identity, ip);
            } catch (Throwable error) {
                getLogger().log(Level.WARNING, "恢复在线会话失败: " + player.getName(), error);
            }
        }
    }

    private void startWeb() {
        if (!config.web().enable()) {
            getLogger().info("Web 面板已按配置关闭。");
            return;
        }
        webServer = createWeb();
        if (webServer == null) {
            getLogger().severe("Web 面板启动失败，管理/绑定接口不可用。");
        } else if (config.web().adminKeyAutoGenerated()) {
            // The loader already printed the generated key warning; only repeat the
            // actionable hint here so the value is not written to the log twice.
            getLogger().warning("Web 面板使用自动生成的管理密钥（已在启动日志与 config.yml 中给出），请立即保存。");
        }
    }

    private WebServer createWeb() {
        if (!config.web().enable()) {
            return null;
        }
        if (!config.web().hasUsableAdminKey()) {
            getLogger().severe("web.admin-key 为空或强度不足（至少 24 字符、10 种不同字符）；Web 面板未启动。");
            return null;
        }
        WebServer server = new WebServer(this);
        if (!server.start()) {
            server.stop();
            return null;
        }
        return server;
    }

    /**
     * bStats collected its data when constructed, so changing the plugin id means
     * abandoning the current client. Returns true when the client was dropped.
     */
    private boolean disableBStatsIfNeeded() {
        CarbonGuardConfig current = config;
        Metrics metrics = bStats;
        if (metrics == null || (current != null && current.bstats().pluginId() == bStatsPluginId)) {
            return false;
        }
        bStats = null;
        bStatsPluginId = 0;
        getLogger().info("bStats plugin-id 已变更；旧客户端停止上报，需重启服务器以按新 id 上报。");
        return true;
    }

    /**
     * Starts the optional bStats client. Charts contain only configuration/module
     * state; no player names, IPs, emails or secrets are submitted.
     */
    private void startBStats() {
        CarbonGuardConfig current = config;
        if (current == null || !current.bstats().enable() || current.bstats().pluginId() <= 0) {
            if (current != null && current.bstats().enable()) {
                getLogger().info("bStats 未启用：请在 bStats 注册插件并填写 bstats.plugin-id。");
            }
            return;
        }
        if (bStats != null) {
            return;
        }
        try {
            Metrics metrics = new Metrics(this, current.bstats().pluginId());
            metrics.addCustomChart(new SimplePie("ip_limit_mode",
                    () -> config.ipLimit().mode().name()));
            metrics.addCustomChart(new SimplePie("ip_association_action",
                    () -> config.ban().ipAssociation().action().name()));
            metrics.addCustomChart(new SimplePie("identity_backend", () -> {
                if (identityResolver.floodgatePresent()) {
                    return "floodgate";
                }
                return identityResolver.geyserPresent() ? "geyser" : "java";
            }));
            metrics.addCustomChart(new SimplePie("binding_gate",
                    () -> bindingGateOperational() ? "enabled" : "disabled"));
            metrics.addCustomChart(new SimplePie("web_enabled",
                    () -> config.web().enable() ? "enabled" : "disabled"));
            metrics.addCustomChart(new AdvancedPie("enabled_modules", () -> {
                Map<String, Integer> values = new HashMap<>();
                if (config.ipLimit().enable()) {
                    values.put("ip_limit", 1);
                }
                if (config.ban().enable()) {
                    values.put("ban", 1);
                }
                if (config.ban().ipAssociation().enable()) {
                    values.put("ip_association", 1);
                }
                if (config.emailVerify().enable()) {
                    values.put("email_verify", 1);
                }
                if (config.web().enable()) {
                    values.put("web", 1);
                }
                if (config.bstats().enable() && config.bstats().pluginId() > 0) {
                    values.put("bstats", 1);
                }
                if (config.updateCheck().enable()) {
                    values.put("auto_update", 1);
                }
                return values;
            }));
            bStats = metrics;
            bStatsPluginId = current.bstats().pluginId();
            getLogger().info("bStats 匿名统计已启用（plugin-id=" + current.bstats().pluginId() + "）。");
        } catch (Throwable error) {
            getLogger().log(Level.WARNING, "bStats 初始化失败，匿名统计关闭", error);
        }
    }

    public boolean bindingGateOperational() {
        CarbonGuardConfig current = config;
        WebServer server = webServer;
        MailService mail = mailService;
        return bindingGateConfigurationComplete()
                && mail != null
                && mail.operational()
                && server != null
                && server.isRunning();
    }

    private boolean bindingGateConfigurationComplete() {
        CarbonGuardConfig current = config;
        return current != null
                && current.emailVerify().enable()
                && current.emailVerify().requireBinding()
                && current.smtp().configured()
                && current.web().enable()
                && current.web().hasUsableAdminKey()
                && current.web().hasReachablePublicUrl();
    }

    /** Fail-closed window while an already-enforced binding gate is being restarted. */
    public boolean bindingGateMaintenance() {
        return reloading && bindingGateConfigurationComplete();
    }

    private void probeMail() {
        MailService mail = mailService;
        if (mail == null) {
            return;
        }
        try {
            mail.probe().whenComplete((healthy, error) -> {
                if (error != null) {
                    getLogger().log(Level.WARNING, "SMTP 健康检查异常", error);
                } else if (Boolean.TRUE.equals(healthy)) {
                    boolean required = config.emailVerify().enable() && config.emailVerify().requireBinding();
                    getLogger().info(required
                            ? "SMTP 可用，入服拦截状态: " + (bindingGateOperational() ? "开启" : "关闭")
                            : "SMTP 健康检查通过。");
                } else if (config.emailVerify().enable() && config.emailVerify().requireBinding()) {
                    getLogger().warning("SMTP 不可用，入服拦截保持关闭。");
                } else {
                    getLogger().info("SMTP 当前不可用（未启用入服拦截，不影响登录）。");
                }
            });
        } catch (Throwable error) {
            getLogger().log(Level.WARNING, "无法启动 SMTP 健康检查", error);
        }
    }

    private synchronized void scheduleTasks() {
        cancelTasks();
        long cacheSeconds = Math.max(5, config.storage().pool().cacheRefreshSeconds());
        tasks.add(getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                data.refreshCaches();
            } catch (Throwable error) {
                getLogger().log(Level.WARNING, "定时刷新登录缓存失败，将继续使用上一份缓存", error);
            }
        }, cacheSeconds * 20L, cacheSeconds * 20L));

        tasks.add(getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            data.sessions().cleanup();
            bindTickets.cleanup();
            WebServer server = webServer;
            if (server != null) {
                server.cleanupRateLimits();
            }
        }, 30L * 20L, 30L * 20L));

        if (config.stats().enable()) {
            tasks.add(getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                int days = config.stats().retentionDays();
                data.database().write(connection -> data.stats().purge(connection, TimeUtil.now(), days));
            }, 6L * 60L * 60L * 20L, 6L * 60L * 60L * 20L));
        }

        if (config.updateCheck().enable()) {
            long minutes = Math.max(5, config.updateCheck().intervalMinutes());
            long period = minutes * 60L * 20L;
            tasks.add(getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                UpdateService service = updateService;
                if (service != null) {
                    // checkNow performs HTTP + file I/O only on this async timer thread.
                    service.checkNow();
                }
            }, 60L * 20L, period));
        }
    }

    private synchronized void cancelTasks() {
        for (BukkitTask task : tasks) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // Scheduler may already be shutting down.
            }
        }
        tasks.clear();
    }

    /**
     * Reloads config/messages and restarts the HTTP listener. Must be called off the
     * main thread because cache refresh and listener shutdown can block briefly.
     */
    public synchronized ReloadResult reloadCguard() throws Exception {
        ConfigLoader.LoadResult loaded = ConfigLoader.loadFile(
                getDataFolder().toPath().resolve("config.yml"), getDataFolder().toPath());
        CarbonGuardConfig next = loaded.config();
        CarbonGuardConfig previous = config;
        Messages previousMessages = messages;
        AdminService previousAdmin = adminService;
        WebServer previousWeb = webServer;
        List<String> warnings = new ArrayList<>(loaded.warnings());
        if (!previous.storage().sqliteFile().equals(next.storage().sqliteFile())) {
            warnings.add("storage.sqlite-file 变更需要重启服务器才会生效；本次仍使用原数据库。");
        }
        if (previous.storage().pool().loginMaximumSize() != next.storage().pool().loginMaximumSize()
                || previous.storage().pool().webMaximumSize() != next.storage().pool().webMaximumSize()) {
            warnings.add("连接池大小变更需要重启服务器才会生效。");
        }

        Messages nextMessages = Messages.load(this);
        boolean canReuseWeb = previousWeb != null && previousWeb.isRunning()
                && next.web().enable() && next.web().hasUsableAdminKey()
                && next.web().publicUrl().equals(previous.web().publicUrl())
                && sameWebEndpoint(previous, next);
        config = next;
        messages = nextMessages;
        adminService = new AdminService(data, config, bindTickets);
        data.setRecordStats(next.stats().enable());
        accessPolicy.refresh();
        if (disableBStatsIfNeeded()) {
            warnings.add("bstats 已关闭；需重启服务器后才会按新配置重新上报。");
        }

        if (config.web().adminKeyAutoGenerated()) {
            getLogger().warning("web.admin-key 为空，已自动生成随机管理密钥（见上方配置警告）："
                    + config.web().adminKey());
            try {
                ConfigLoader.persistAdminKey(getDataFolder().toPath().resolve("config.yml"),
                        config.web().adminKey());
                warnings.add("已自动生成随机管理密钥并写入 config.yml，请立即保存。");
            } catch (Exception e) {
                warnings.add("已自动生成随机管理密钥但写入 config.yml 失败: " + e.getMessage());
            }
        }

        if (!canReuseWeb) {
            boolean wasReloading = reloading;
            if (next.web().enable()) {
                reloading = true;
            }
            try {
                if (previousWeb != null) {
                    previousWeb.stop();
                    webServer = null;
                }
                if (next.web().enable()) {
                    WebServer started = createWeb();
                    if (started == null) {
                        // Restore the previous coherent state instead of committing a
                        // config whose web listener cannot run.
                        config = previous;
                        messages = previousMessages;
                        adminService = previousAdmin;
                        accessPolicy.refresh();
                        data.setRecordStats(previous.stats().enable());
                        if (previous.web().enable() && previous.web().hasUsableAdminKey()) {
                            webServer = createWeb();
                        }
                        throw new IllegalStateException("Web 面板启动失败，已回滚到旧配置");
                    }
                    webServer = started;
                    if (next.web().adminKeyAutoGenerated()) {
                        warnings.add("可用下方日志中的随机管理密钥登录 Web 面板；登录后请更换为自己的强密钥。");
                    }
                }
            } finally {
                reloading = wasReloading;
            }
        }

        probeMail();
        startBStats();
        try {
            data.refreshCaches();
        } catch (Exception e) {
            warnings.add("缓存刷新失败，继续使用上一份缓存: " + e.getMessage());
            getLogger().log(Level.WARNING, "重载后缓存刷新失败", e);
        }
        scheduleTasks();
        if (config.web().adminKeyAutoGenerated() && webServer == null) {
            warnings.add("随机管理密钥已生成，但 Web 面板未启动；请检查 web 配置。");
        }
        if (config.emailVerify().enable() && config.emailVerify().requireBinding()
                && !bindingGateOperational()) {
            warnings.add("入服拦截因 SMTP/Web/密钥/public-url 不完整或 SMTP 不可用而关闭。");
        }
        return new ReloadResult(bindingGateOperational(), webServer != null && webServer.isRunning(),
                List.copyOf(warnings));
    }

    private static boolean sameWebEndpoint(CarbonGuardConfig a, CarbonGuardConfig b) {
        return a.web().enable() == b.web().enable()
                && a.web().bindAddress().equals(b.web().bindAddress())
                && a.web().port() == b.web().port();
    }

    @Override
    public void onDisable() {
        cancelTasks();
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (bindTickets != null) {
            bindTickets.clear();
        }
        if (mailService != null) {
            mailService.close();
        }
        bStats = null;
        bStatsPluginId = 0;
        if (data != null) {
            data.close();
        }
        getLogger().info("CarbonGuard 已停用。");
    }

    /**
     * Web decisions run on HTTP/DB threads; kicking must happen on the main thread.
     * XUID identities are matched by the recorded player name, UUID identities by UUID.
     */
    public void kickOnlineIdentity(String identityKey, String playerName,
                                   net.kyori.adventure.text.Component message) {
        if (identityKey == null || !isEnabled()) {
            return;
        }
        Runnable action = () -> {
            Player target = null;
            if (identityKey.startsWith("uuid:")) {
                try {
                    target = getServer().getPlayer(java.util.UUID.fromString(identityKey.substring(5)));
                } catch (IllegalArgumentException ignored) {
                    // Fall through to the name lookup.
                }
            }
            if (target == null && playerName != null) {
                target = getServer().getPlayerExact(playerName);
            }
            if (target != null && target.isOnline()) {
                target.kick(message);
            }
        };
        try {
            getServer().getScheduler().runTask(this, action);
        } catch (Throwable ignored) {
            // Server is shutting down.
        }
    }

    public void debug(String message) {
        if (config != null && config.debug()) {
            getLogger().info("[debug] " + message);
        }
    }

    public CarbonGuardConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public DataStore data() {
        return data;
    }

    public IdentityResolver identityResolver() {
        return identityResolver;
    }

    public AccessPolicy accessPolicy() {
        return accessPolicy;
    }

    public MailService mailService() {
        return mailService;
    }

    public BindTicketService bindTicketService() {
        return bindTickets;
    }

    public AuthService authService() {
        return authService;
    }

    public LoginGate loginGate() {
        return loginGate;
    }

    public BanService banService() {
        return banService;
    }

    public ReviewService reviewService() {
        return reviewService;
    }

    public AdminService adminService() {
        return adminService;
    }

    public WebServer webServer() {
        return webServer;
    }

    public UpdateService updateService() {
        return updateService;
    }

    public LoginContexts loginContexts() {
        return loginContexts;
    }

    public record ReloadResult(boolean bindingGateOperational, boolean webStarted, List<String> warnings) {
    }
}
