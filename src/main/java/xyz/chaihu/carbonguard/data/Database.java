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

package xyz.chaihu.carbonguard.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;

/**
 * SQLite + HikariCP data layer.
 *
 * <p>Two physically separate pools are intentional: cache refreshes and writes use
 * {@code core}, while web-panel queries use {@code web}. A slow statistics query can
 * therefore never exhaust the connections needed by the login-side cache refresher.
 * Writes are serialised on one thread to keep SQLite lock contention predictable.</p>
 */
public final class Database implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS identities (
                identity_key TEXT PRIMARY KEY,
                identity_type TEXT NOT NULL,
                xuid TEXT,
                observed_xuid TEXT,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                identity_source TEXT NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                last_ip TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_identities_uuid ON identities(uuid)",
            "CREATE INDEX IF NOT EXISTS idx_identities_name ON identities(player_name COLLATE NOCASE)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_identities_xuid ON identities(xuid) WHERE xuid IS NOT NULL",
            """
            CREATE TABLE IF NOT EXISTS bindings (
                identity_key TEXT PRIMARY KEY
                    REFERENCES identities(identity_key) ON UPDATE CASCADE ON DELETE CASCADE,
                email TEXT NOT NULL COLLATE NOCASE,
                qq TEXT NOT NULL,
                verified_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """,
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_bindings_email ON bindings(email COLLATE NOCASE)",
            "CREATE INDEX IF NOT EXISTS idx_bindings_qq ON bindings(qq)",
            """
            CREATE TABLE IF NOT EXISTS ip_history (
                identity_key TEXT NOT NULL
                    REFERENCES identities(identity_key) ON UPDATE CASCADE ON DELETE CASCADE,
                ip TEXT NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (identity_key, ip)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_ip_history_ip ON ip_history(ip)",
            """
            CREATE TABLE IF NOT EXISTS bans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                identity_key TEXT NOT NULL
                    REFERENCES identities(identity_key) ON UPDATE CASCADE ON DELETE CASCADE,
                player_name TEXT NOT NULL,
                reason TEXT NOT NULL,
                created_by TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL DEFAULT 0,
                active INTEGER NOT NULL DEFAULT 1,
                lifted_at INTEGER NOT NULL DEFAULT 0,
                lifted_by TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_bans_identity ON bans(identity_key, active, expires_at)",
            """
            CREATE TABLE IF NOT EXISTS ban_ip_assoc (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ban_id INTEGER NOT NULL REFERENCES bans(id) ON DELETE CASCADE,
                ip TEXT NOT NULL,
                source_identity TEXT NOT NULL,
                recorded_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_ban_ip_assoc_ip ON ban_ip_assoc(ip, expires_at)",
            "CREATE INDEX IF NOT EXISTS idx_ban_ip_assoc_ban ON ban_ip_assoc(ban_id)",
            """
            CREATE TABLE IF NOT EXISTS reviews (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                identity_key TEXT NOT NULL,
                player_name TEXT,
                ip TEXT,
                ban_id INTEGER REFERENCES bans(id) ON DELETE SET NULL,
                reason TEXT,
                status TEXT NOT NULL DEFAULT 'PENDING',
                created_at INTEGER NOT NULL,
                reviewed_at INTEGER NOT NULL DEFAULT 0,
                reviewed_by TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_reviews_status ON reviews(status, created_at)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_reviews_pending_unique ON reviews(type, identity_key, ip) WHERE status = 'PENDING'",
            """
            CREATE TABLE IF NOT EXISTS login_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                identity_key TEXT NOT NULL,
                player_name TEXT NOT NULL,
                ip TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                detail TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_login_events_time ON login_events(occurred_at)",
            "CREATE INDEX IF NOT EXISTS idx_login_events_identity ON login_events(identity_key, occurred_at)",
            """
            CREATE TABLE IF NOT EXISTS bind_tickets (
                token_hash TEXT PRIMARY KEY,
                identity_key TEXT NOT NULL,
                player_name TEXT NOT NULL,
                ip TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                used_at INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_bind_tickets_identity ON bind_tickets(identity_key, expires_at)",
            """
            CREATE TABLE IF NOT EXISTS email_challenges (
                challenge_hash TEXT PRIMARY KEY,
                ticket_hash TEXT NOT NULL,
                identity_key TEXT NOT NULL,
                player_name TEXT NOT NULL,
                email TEXT NOT NULL COLLATE NOCASE,
                code_salt TEXT NOT NULL,
                code_hash TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                consumed_at INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_email_challenges_ticket ON email_challenges(ticket_hash)",
            "CREATE INDEX IF NOT EXISTS idx_email_challenges_expiry ON email_challenges(expires_at)",
            """
            CREATE TABLE IF NOT EXISTS identity_aliases (
                old_key TEXT PRIMARY KEY,
                new_key TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
    );

    private final CarbonGuardConfig config;
    private final Logger logger;
    private final ThreadPoolExecutor writes;
    private final ExecutorService webQueries;
    private final AtomicBoolean closed = new AtomicBoolean();
    private HikariDataSource core;
    private HikariDataSource web;

    public Database(CarbonGuardConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.writes = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), threadFactory("carbonguard-db-write", true),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.webQueries = Executors.newFixedThreadPool(
                Math.max(1, config.storage().pool().webMaximumSize()),
                threadFactory("carbonguard-web-query", true));
    }

    public void init() throws Exception {
        Path file = config.storage().sqliteFile();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        core = createPool("carbonguard-core", config.storage().pool().loginMaximumSize());
        web = createPool("carbonguard-web", config.storage().pool().webMaximumSize());
        try (Connection connection = core.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA busy_timeout=10000");
            }
            createSchema(connection);
        }
        logger.info("SQLite 数据层已就绪: " + file.toAbsolutePath());
    }

    private HikariDataSource createPool(String name, int size) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(name);
        hikari.setJdbcUrl("jdbc:sqlite:" + config.storage().sqliteFile().toAbsolutePath());
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(size);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(config.storage().pool().connectionTimeoutMillis());
        hikari.setValidationTimeout(Math.min(5_000, config.storage().pool().connectionTimeoutMillis()));
        hikari.setMaxLifetime(0);
        hikari.setIdleTimeout(0);
        hikari.setKeepaliveTime(0);
        hikari.setAutoCommit(true);
        hikari.addDataSourceProperty("busy_timeout", "10000");
        hikari.addDataSourceProperty("foreign_keys", "true");
        hikari.addDataSourceProperty("journal_mode", "WAL");
        hikari.addDataSourceProperty("synchronous", "NORMAL");
        return new HikariDataSource(hikari);
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_meta (
                        k TEXT PRIMARY KEY,
                        v TEXT NOT NULL
                    )
                    """);
        }
        int existing;
        try (PreparedStatement ps = connection.prepareStatement("SELECT v FROM schema_meta WHERE k='version'");
             ResultSet rs = ps.executeQuery()) {
            existing = rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
        if (existing > SCHEMA_VERSION) {
            throw new SQLException("数据库 schema 版本 " + existing + " 高于插件支持的 " + SCHEMA_VERSION
                    + "，请升级插件或恢复备份");
        }
        if (existing < SCHEMA_VERSION) {
            try (Statement statement = connection.createStatement()) {
                for (String ddl : SCHEMA) {
                    statement.execute(ddl);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO schema_meta(k,v) VALUES('version',?) "
                            + "ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
                ps.setString(1, Integer.toString(SCHEMA_VERSION));
                ps.executeUpdate();
            }
            logger.info("数据库 schema 已初始化/升级到 v" + SCHEMA_VERSION);
        }
    }

    public Connection coreConnection() throws SQLException {
        return core.getConnection();
    }

    public Connection webConnection() throws SQLException {
        return web.getConnection();
    }

    /** Executes a short blocking statement on the calling thread (startup/migrations). */
    public <T> T coreNow(SqlCall<T> call) throws SQLException {
        try (Connection connection = core.getConnection()) {
            return call.apply(connection);
        }
    }

    /** Executes a short blocking web-pool statement on the calling thread. */
    public <T> T webNow(SqlCall<T> call) throws SQLException {
        try (Connection connection = web.getConnection()) {
            return call.apply(connection);
        }
    }

    public <T> CompletableFuture<T> webAsync(SqlCall<T> call) {
        CompletableFuture<T> future = new CompletableFuture<>();
        webQueries.execute(() -> {
            try (Connection connection = web.getConnection()) {
                future.complete(call.apply(connection));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Queues a write. Ordering is preserved by the single writer thread. SQLite only
     * has one writer, so adding more threads here would just increase lock timeouts.
     */
    public CompletableFuture<Void> write(SqlWork work) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        writes.execute(() -> {
            try (Connection connection = core.getConnection()) {
                work.apply(connection);
                future.complete(null);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "数据库写入失败", t);
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public <T> CompletableFuture<T> writeTransaction(SqlCall<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        writes.execute(() -> {
            try (Connection connection = core.getConnection()) {
                boolean oldAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = work.apply(connection);
                    connection.commit();
                    future.complete(result);
                } catch (Throwable t) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackError) {
                        t.addSuppressed(rollbackError);
                    }
                    throw t;
                } finally {
                    connection.setAutoCommit(oldAutoCommit);
                }
            } catch (Throwable t) {
                if (t instanceof SQLException) {
                    logger.log(Level.WARNING, "数据库事务失败", t);
                } else {
                    logger.log(Level.FINE, "事务被业务校验回滚", t);
                }
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        writes.shutdown();
        try {
            if (!writes.awaitTermination(10, TimeUnit.SECONDS)) {
                writes.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writes.shutdownNow();
        }
        webQueries.shutdown();
        try {
            if (!webQueries.awaitTermination(5, TimeUnit.SECONDS)) {
                webQueries.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            webQueries.shutdownNow();
        }
        if (web != null) {
            web.close();
        }
        if (core != null) {
            core.close();
        }
    }

    private static ThreadFactory threadFactory(String prefix, boolean daemon) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(daemon);
            thread.setContextClassLoader(Database.class.getClassLoader());
            return thread;
        };
    }

    @FunctionalInterface
    public interface SqlCall<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlWork {
        void apply(Connection connection) throws SQLException;
    }
}
