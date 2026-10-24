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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.cache.BanCache;
import xyz.chaihu.carbonguard.data.cache.BindingCache;
import xyz.chaihu.carbonguard.data.cache.ReviewAllowCache;
import xyz.chaihu.carbonguard.data.cache.SessionRegistry;
import xyz.chaihu.carbonguard.data.model.AssociationHit;
import xyz.chaihu.carbonguard.data.model.BanRecord;
import xyz.chaihu.carbonguard.data.model.Binding;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.data.model.ReviewAllowEntry;
import xyz.chaihu.carbonguard.data.repo.BanRepository;
import xyz.chaihu.carbonguard.data.repo.BindingRepository;
import xyz.chaihu.carbonguard.data.repo.IdentityRepository;
import xyz.chaihu.carbonguard.data.repo.PlayerRepository;
import xyz.chaihu.carbonguard.data.repo.ReviewRepository;
import xyz.chaihu.carbonguard.data.repo.StatsRepository;
import xyz.chaihu.carbonguard.data.repo.VerificationRepository;
import xyz.chaihu.carbonguard.data.repo.VerificationCodeRepository;
import xyz.chaihu.carbonguard.util.TimeUtil;

/** Composition root for the persistence layer and its login-path caches. */
public final class DataStore implements AutoCloseable {
    private final CarbonGuardConfig config;
    private final Logger logger;
    private final Database database;
    private final IdentityRepository identities = new IdentityRepository();
    private final BindingRepository bindings = new BindingRepository();
    private final BanRepository bans = new BanRepository();
    private final ReviewRepository reviews = new ReviewRepository();
    private final StatsRepository stats = new StatsRepository();
    private final VerificationRepository verification = new VerificationRepository();
    private final VerificationCodeRepository codes = new VerificationCodeRepository();
    private final PlayerRepository players = new PlayerRepository();
    private final BindingCache bindingCache = new BindingCache();
    private final BanCache banCache = new BanCache();
    private final ReviewAllowCache reviewCache = new ReviewAllowCache();
    private final SessionRegistry sessions = new SessionRegistry();
    private volatile boolean recordStats;

    public DataStore(CarbonGuardConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.recordStats = config.stats().enable();
        this.database = new Database(config, logger);
    }

    public void init(boolean floodgateAvailable) throws Exception {
        database.init();
        if (floodgateAvailable) {
            migrateFallbackIdentities();
        }
        backfillPlayerProfiles();
        refreshCaches();
        logger.info("登录缓存已预热: bindings=" + bindingCache.size()
                + ", activeBans=" + banCache.banCount()
                + ", ipAssociations=" + banCache.associationCount());
    }

    private void backfillPlayerProfiles() {
        if (!config.players().enable()) {
            return;
        }
        try {
            int created = database.coreNow(connection ->
                    players.backfillFromIdentities(connection, TimeUtil.now()));
            if (created > 0) {
                logger.info("玩家数据：已为 " + created + " 个历史身份补齐记录");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "玩家数据补齐失败", e);
        }
    }

    /**
     * Rebuilds all read caches. This is called at startup, on reload and periodically
     * by the plugin scheduler; it never runs on the pre-login path. All queries share
     * one connection and one read snapshot.
     */
    public void refreshCaches() throws SQLException {
        long now = TimeUtil.now();
        long bindingVersion = bindingCache.version();
        long banVersion = banCache.version();
        long reviewVersion = reviewCache.version();
        Caches loaded = database.coreNow(connection -> new Caches(
                bindings.loadAll(connection),
                bans.loadActiveBans(connection, now),
                bans.loadActiveAssociations(connection, now),
                reviews.loadActiveAllowlist(connection, now)));
        // A write-through may have landed while the queries ran. In that case the
        // snapshot is already newer than the query; skip it rather than roll back.
        bindingCache.replaceAllIfUnchanged(loaded.bindings(), bindingVersion);
        banCache.replaceAllIfUnchanged(loaded.bans(), loaded.associations(), banVersion);
        reviewCache.replaceAllIfUnchanged(loaded.reviews(), reviewVersion);
    }

    private record Caches(Map<String, Binding> bindings, List<BanRecord> bans,
                          List<AssociationHit> associations, List<ReviewAllowEntry> reviews) {
    }

    /**
     * If a UUID_FALLBACK record captured an authoritative XUID from Geyser, and
     * Floodgate is now installed, migrate it to the XUID key before caches are loaded.
     * No XUID is ever inferred from the shape of a UUID.
     */
    private void migrateFallbackIdentities() throws SQLException {
        List<PlayerIdentity> candidates = database.coreNow(identities::fallbackWithObservedXuid);
        for (PlayerIdentity old : candidates) {
            String newKey = "xuid:" + old.observedXuid();
            try {
                database.coreNow(connection -> {
                    migrateOne(connection, old, newKey);
                    return null;
                });
                logger.info("已将基岩版降级身份 " + old.key() + " 迁移到 " + newKey);
            } catch (SQLException e) {
                logger.log(Level.WARNING, "迁移降级身份失败: " + old.key(), e);
            }
        }
    }

    private void migrateOne(Connection connection, PlayerIdentity old, String newKey) throws SQLException {
        boolean targetExists;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM identities WHERE identity_key=?")) {
            ps.setString(1, newKey);
            try (ResultSet rs = ps.executeQuery()) {
                targetExists = rs.next();
            }
        }
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (!targetExists) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        UPDATE identities SET identity_key=?, identity_type='XUID', xuid=?,
                            observed_xuid=NULL, identity_source='FLOODGATE'
                        WHERE identity_key=?
                        """)) {
                    ps.setString(1, newKey);
                    ps.setString(2, old.observedXuid());
                    ps.setString(3, old.key());
                    ps.executeUpdate();
                }
                updateNonForeignKeyReferences(connection, old.key(), newKey);
            } else {
                // Merge history and restrictions into the existing XUID identity.
                try (PreparedStatement ps = connection.prepareStatement("""
                        UPDATE OR IGNORE bindings SET identity_key=? WHERE identity_key=?
                        """)) {
                    ps.setString(1, newKey);
                    ps.setString(2, old.key());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COUNT(*) FROM bindings WHERE identity_key=?")) {
                    ps.setString(1, old.key());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            logger.warning("迁移 " + old.key() + " -> " + newKey
                                    + " 时目标身份已有绑定，旧绑定 " + rs.getInt(1)
                                    + " 条被丢弃（保留目标身份绑定）");
                        }
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM bindings WHERE identity_key=?")) {
                    ps.setString(1, old.key());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO ip_history(identity_key, ip, first_seen, last_seen, seen_count)
                        SELECT ?, ip, first_seen, last_seen, seen_count FROM ip_history WHERE identity_key=?
                        ON CONFLICT(identity_key, ip) DO UPDATE SET
                            first_seen=MIN(ip_history.first_seen, excluded.first_seen),
                            last_seen=MAX(ip_history.last_seen, excluded.last_seen),
                            seen_count=ip_history.seen_count + excluded.seen_count
                        """)) {
                    ps.setString(1, newKey);
                    ps.setString(2, old.key());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM ip_history WHERE identity_key=?")) {
                    ps.setString(1, old.key());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE bans SET identity_key=? WHERE identity_key=?")) {
                    ps.setString(1, newKey);
                    ps.setString(2, old.key());
                    ps.executeUpdate();
                }
                updateNonForeignKeyReferences(connection, old.key(), newKey);
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM identities WHERE identity_key=?")) {
                    ps.setString(1, old.key());
                    ps.executeUpdate();
                }
            }
            identities.addAlias(connection, old.key(), newKey, TimeUtil.now());
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private void updateNonForeignKeyReferences(Connection connection, String oldKey, String newKey)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE ban_ip_assoc SET source_identity=? WHERE source_identity=?")) {
            ps.setString(1, newKey);
            ps.setString(2, oldKey);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE reviews SET identity_key=? WHERE identity_key=?")) {
            ps.setString(1, newKey);
            ps.setString(2, oldKey);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE login_events SET identity_key=? WHERE identity_key=?")) {
            ps.setString(1, newKey);
            ps.setString(2, oldKey);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE bind_tickets SET identity_key=? WHERE identity_key=?")) {
            ps.setString(1, newKey);
            ps.setString(2, oldKey);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE email_challenges SET identity_key=? WHERE identity_key=?")) {
            ps.setString(1, newKey);
            ps.setString(2, oldKey);
            ps.executeUpdate();
        }
    }

    public void recordLogin(PlayerIdentity identity, String ip, String outcome, String detail) {
        long now = TimeUtil.now();
        database.writeTransaction(connection -> {
            identities.upsert(connection, identity, ip, now);
            if (!ip.startsWith("0.0.0.0")) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO ip_history(identity_key, ip, first_seen, last_seen, seen_count)
                        VALUES(?,?,?,?,1)
                        ON CONFLICT(identity_key, ip) DO UPDATE SET
                            last_seen=excluded.last_seen, seen_count=ip_history.seen_count+1
                        """)) {
                    ps.setString(1, identity.key());
                    ps.setString(2, ip);
                    ps.setLong(3, now);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
            }
            if (recordStats) {
                stats.recordLogin(connection, identity.key(), identity.playerName(), ip, outcome, detail, now);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.FINE, "记录登录事件失败", error);
            }
        });
    }

    /**
     * Marks the first successful join and bumps the login counter. The identity row
     * is upserted in the same transaction so the profile FK is always satisfied
     * (e.g. a player who passed pre-login but crashed before the queued login write).
     */
    public void recordJoin(PlayerIdentity identity, String ip, long now) {
        String effectiveIp = ip == null || ip.isBlank() ? "0.0.0.0" : ip;
        database.writeTransaction(connection -> {
            identities.upsert(connection, identity, effectiveIp, now);
            if (config.players().enable()) {
                players.join(connection, identity.key(), identity.playerName(), now);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.FINE, "记录玩家加入失败", error);
            }
        });
    }

    /**
     * Records the online interval for the player's profile. Called on quit; the
     * write is queued so the vacating main thread is never blocked.
     */
    public void recordPlaytime(PlayerIdentity identity, long onlineMillis) {
        long seconds = Math.max(0L, onlineMillis / 1_000L);
        database.write(connection -> players.addPlaytime(connection, identity.key(), seconds))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.log(Level.FINE, "记录游玩时长失败", error);
                    }
                });
    }

    public void touchIdentity(PlayerIdentity identity, String ip) {
        long now = TimeUtil.now();
        database.writeTransaction(connection -> {
            identities.touch(connection, identity, ip, now);
            if (ip.startsWith("0.0.0.0")) {
                return null;
            }
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO ip_history(identity_key, ip, first_seen, last_seen, seen_count)
                    VALUES(?,?,?,?,1)
                    ON CONFLICT(identity_key, ip) DO UPDATE SET
                        last_seen=excluded.last_seen, seen_count=ip_history.seen_count+1
                    """)) {
                ps.setString(1, identity.key());
                ps.setString(2, ip);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void queueReview(xyz.chaihu.carbonguard.data.model.ReviewType type, PlayerIdentity identity,
                            String ip, Long banId, String reason) {
        long now = TimeUtil.now();
        database.write(connection ->
                reviews.insertIfAbsent(connection, type, identity.key(), identity.playerName(),
                        ip, banId, reason, now));
    }

    public void setRecordStats(boolean recordStats) {
        this.recordStats = recordStats;
    }

    public Database database() {
        return database;
    }

    public IdentityRepository identities() {
        return identities;
    }

    public BindingRepository bindings() {
        return bindings;
    }

    public BanRepository bans() {
        return bans;
    }

    public ReviewRepository reviews() {
        return reviews;
    }

    public StatsRepository stats() {
        return stats;
    }

    public VerificationRepository verification() {
        return verification;
    }

    public VerificationCodeRepository codes() {
        return codes;
    }

    public PlayerRepository players() {
        return players;
    }

    public BindingCache bindingCache() {
        return bindingCache;
    }

    public BanCache banCache() {
        return banCache;
    }

    public ReviewAllowCache reviewCache() {
        return reviewCache;
    }

    public SessionRegistry sessions() {
        return sessions;
    }

    public Optional<Binding> bindingOf(String identityKey) {
        return Optional.ofNullable(bindingCache.get(identityKey));
    }

    @Override
    public void close() {
        database.close();
    }
}
