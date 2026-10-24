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

package xyz.chaihu.carbonguard.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import xyz.chaihu.carbonguard.config.CarbonGuardConfig;
import xyz.chaihu.carbonguard.data.DataStore;
import xyz.chaihu.carbonguard.data.model.BindTicketRecord;
import xyz.chaihu.carbonguard.data.model.PlayerIdentity;
import xyz.chaihu.carbonguard.util.TimeUtil;
import xyz.chaihu.carbonguard.util.Tokens;

/**
 * Bearer tickets that let an unbound player reach the public binding form.
 *
 * <p>The ticket is generated in memory during the async pre-login callback, so the
 * login thread never waits for the database. Only the hash is persisted, as an audit
 * trail; the in-memory ticket is the authority and expires with the process.</p>
 */
public final class BindTicketService {
    private final DataStore data;
    private final Supplier<CarbonGuardConfig> configSupplier;
    private final Map<String, Ticket> byHash = new ConcurrentHashMap<>();
    private final Map<String, String> hashByIdentity = new ConcurrentHashMap<>();

    public BindTicketService(DataStore data, Supplier<CarbonGuardConfig> configSupplier) {
        this.data = data;
        this.configSupplier = configSupplier;
    }

    public synchronized Optional<Ticket> issue(PlayerIdentity identity, String ip) {
        cleanup();
        CarbonGuardConfig config = configSupplier.get();
        long now = TimeUtil.now();
        String existingHash = hashByIdentity.get(identity.key());
        if (existingHash != null) {
            Ticket existing = byHash.get(existingHash);
            if (existing != null && existing.expiresAt() > now) {
                return Optional.of(existing);
            }
            remove(existingHash);
        }
        String raw = Tokens.random(32);
        String hash = Tokens.sha256(raw);
        long expiresAt = now + config.emailVerify().ticketTtlSeconds() * 1_000L;
        Ticket ticket = new Ticket(raw, hash, identity, ip, now, expiresAt);
        byHash.put(hash, ticket);
        hashByIdentity.put(identity.key(), hash);

        data.database().write(connection -> data.verification().insertTicket(connection,
                new BindTicketRecord(hash, identity.key(), identity.playerName(), ip, now, expiresAt, 0)));
        return Optional.of(ticket);
    }

    public Optional<Ticket> find(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 256) {
            return Optional.empty();
        }
        Ticket ticket = byHash.get(Tokens.sha256(raw.trim()));
        if (ticket == null || ticket.expiresAt() <= TimeUtil.now()) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    public Optional<Ticket> findByHash(String hash) {
        Ticket ticket = byHash.get(hash);
        if (ticket == null || ticket.expiresAt() <= TimeUtil.now()) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    public void consumeByHash(String hash) {
        Ticket ticket = byHash.get(hash);
        remove(hash);
        if (ticket != null) {
            long now = TimeUtil.now();
            data.database().write(connection ->
                    data.verification().consumeTicket(connection, hash, now));
        }
    }

    /** Drops the in-memory ticket after the caller consumed it in its own transaction. */
    public void forget(String hash) {
        remove(hash);
    }

    public String urlFor(String raw) {
        String base = configSupplier.get().web().publicUrl();
        return base + "/?bind=" + URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    public void cleanup() {
        long now = TimeUtil.now();
        Iterator<Map.Entry<String, Ticket>> iterator = byHash.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Ticket> entry = iterator.next();
            if (entry.getValue().expiresAt() <= now) {
                iterator.remove();
                hashByIdentity.remove(entry.getValue().identity().key(), entry.getKey());
            }
        }
    }

    public void clear() {
        byHash.clear();
        hashByIdentity.clear();
    }

    /** Drops all live tickets for an identity after an administrative unbind. */
    public synchronized void invalidateIdentity(String identityKey) {
        if (identityKey == null) {
            return;
        }
        String hash = hashByIdentity.remove(identityKey);
        if (hash != null) {
            byHash.remove(hash);
        }
        byHash.entrySet().removeIf(entry -> entry.getValue().identity().key().equals(identityKey));
    }

    private void remove(String hash) {
        Ticket ticket = byHash.remove(hash);
        if (ticket != null) {
            hashByIdentity.remove(ticket.identity().key(), hash);
        }
    }

    public record Ticket(
            String raw,
            String hash,
            PlayerIdentity identity,
            String ip,
            long createdAt,
            long expiresAt
    ) {
    }
}
