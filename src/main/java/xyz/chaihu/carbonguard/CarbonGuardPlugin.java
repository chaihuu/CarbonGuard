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
import java.util.List;
import java.util.logging.Level;
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
    private LoginContexts loginContexts;
    private final List<BukkitTask> tasks = new ArrayList<>();

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
        this.reviewService = new ReviewService(data);
        this.adminService = new AdminService(data, config);
        this.loginContexts = new LoginContexts();
        this.loginGate = new LoginGate(data, data.sessions(), accessPolicy, bindTickets,
                this::messages, this::config, this::bindingGateOperational);

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
        if (config.emailVerify().enable() && config.emailVerify().requireBinding()
                && !bindingGateOperational()) {
            getLogger().severe("未绑定拦截已开启，但 SMTP/Web/管理密钥不完整；为防止锁死服务器，该拦截已自动降级关闭。"
                    + " 修复配置后执行 /carbonguard reload。");
        }
        scheduleTasks();
        getLogger().info("CarbonGuard 已启用 | Paper 26.2 | " + identityResolver.integrationSummary()
                + " | 未绑定拦截=" + (bindingGateOperational() ? "开启" : "关闭"));
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
                data.sessions().tryAcquire(ip, identity.key(), player.getUniqueId(), 0);
                data.sessions().confirm(player.getUniqueId());
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
        if (!config.web().hasUsableAdminKey()) {
            getLogger().severe("web.admin-key 为空或强度不足（至少 24 字符、10 种不同字符）；Web 面板未启动。");
            return;
        }
        webServer = new WebServer(this);
        if (!webServer.start()) {
            getLogger().severe("Web 面板启动失败，管理/绑定接口不可用。");
        }
    }

    public boolean bindingGateOperational() {
        CarbonGuardConfig current = config;
        WebServer server = webServer;
        return current != null
                && current.emailVerify().enable()
                && current.emailVerify().requireBinding()
                && current.smtp().configured()
                && current.web().enable()
                && current.web().hasUsableAdminKey()
                && server != null
                && server.isRunning();
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
        List<String> warnings = new ArrayList<>(loaded.warnings());
        if (!previous.storage().sqliteFile().equals(next.storage().sqliteFile())) {
            warnings.add("storage.sqlite-file 变更需要重启服务器才会生效；本次仍使用原数据库。");
        }
        if (previous.storage().pool().loginMaximumSize() != next.storage().pool().loginMaximumSize()
                || previous.storage().pool().webMaximumSize() != next.storage().pool().webMaximumSize()) {
            warnings.add("连接池大小变更需要重启服务器才会生效。");
        }

        Messages nextMessages = Messages.load(this);
        config = next;
        messages = nextMessages;
        data.setRecordStats(next.stats().enable());
        accessPolicy.refresh();
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        adminService = new AdminService(data, config);
        startWeb();
        data.refreshCaches();
        scheduleTasks();
        if (config.emailVerify().enable() && config.emailVerify().requireBinding()
                && !bindingGateOperational()) {
            warnings.add("未绑定拦截因 SMTP/Web/密钥不完整而关闭。");
        }
        return new ReloadResult(bindingGateOperational(), webServer != null && webServer.isRunning(),
                List.copyOf(warnings));
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

    public LoginContexts loginContexts() {
        return loginContexts;
    }

    public record ReloadResult(boolean bindingGateOperational, boolean webStarted, List<String> warnings) {
    }
}
