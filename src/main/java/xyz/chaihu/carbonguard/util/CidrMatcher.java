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

package xyz.chaihu.carbonguard.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable IPv4/IPv6 CIDR matcher. Bare addresses are accepted as /32 or /128.
 */
public final class CidrMatcher {
    private final List<Rule> rules;

    private CidrMatcher(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static CidrMatcher of(List<String> entries) {
        List<Rule> rules = new ArrayList<>();
        if (entries != null) {
            for (String entry : entries) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                Rule rule = parse(entry);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        }
        return new CidrMatcher(rules);
    }

    public static Rule parse(String input) {
        String value = input.trim();
        String addressPart = value;
        int prefix = -1;
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            addressPart = value.substring(0, slash).trim();
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        byte[] address = IpUtil.bytes(addressPart);
        if (address == null) {
            return null;
        }
        int max = address.length * 8;
        if (prefix < 0) {
            prefix = max;
        }
        if (prefix < 0 || prefix > max) {
            return null;
        }
        return new Rule(address, prefix);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public boolean matches(String ip) {
        if (rules.isEmpty() || ip == null) {
            return false;
        }
        byte[] address = IpUtil.bytes(ip);
        if (address == null) {
            return false;
        }
        for (Rule rule : rules) {
            if (rule.matches(address)) {
                return true;
            }
        }
        return false;
    }

    public record Rule(byte[] network, int prefix) {
        public Rule {
            network = network.clone();
        }

        public boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainder = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            if (remainder == 0) {
                return true;
            }
            int mask = (0xff << (8 - remainder)) & 0xff;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
