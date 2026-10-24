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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * IP normalisation and CIDR matching.
 *
 * <p>All addresses are stored in a canonical, lower-case textual form. IPv4-mapped
 * IPv6 addresses (for example {@code ::ffff:192.0.2.1}) are folded to IPv4 so a
 * player cannot evade an IP rule by connecting through a dual-stack listener.</p>
 */
public final class IpUtil {
    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern ZONE = Pattern.compile("%[0-9A-Za-z_.-]+$");

    private IpUtil() {
    }

    public static String normalize(InetAddress address) {
        if (address == null) {
            return "0.0.0.0";
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && isMappedV4(bytes)) {
            return ipv4ToString(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        }
        if (address instanceof Inet4Address) {
            return ipv4ToString(bytes);
        }
        return canonicalV6(bytes);
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "0.0.0.0";
        }
        String cleaned = ZONE.matcher(value.trim()).replaceFirst("");
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        try {
            return normalize(InetAddress.getByName(cleaned));
        } catch (UnknownHostException e) {
            return cleaned.toLowerCase(Locale.ROOT);
        }
    }

    public static boolean isLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String cleaned = ZONE.matcher(value.trim()).replaceFirst("");
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (IPV4.matcher(cleaned).matches()) {
            String[] parts = cleaned.split("\\.");
            for (String part : parts) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return cleaned.indexOf(':') >= 0;
    }

    public static boolean isLoopback(String normalizedAddress) {
        return "127.0.0.1".equals(normalizedAddress) || "::1".equals(normalizedAddress);
    }

    private static boolean isMappedV4(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static String ipv4ToString(byte[] bytes) {
        return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "." + (bytes[3] & 0xff);
    }

    private static String canonicalV6(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[i * 2] & 0xff) << 8) | (bytes[i * 2 + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLen = 0;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && groups[j] == 0) {
                j++;
            }
            if (j - i > bestLen) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLen - 1;
                continue;
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        return out.isEmpty() ? "::" : out.toString();
    }

    /**
     * Returns the raw network bytes for an address literal, or {@code null}.
     */
    public static byte[] bytes(String literal) {
        if (!isLiteral(literal)) {
            return null;
        }
        String cleaned = ZONE.matcher(literal.trim()).replaceFirst("");
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        try {
            return InetAddress.getByName(cleaned).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public static String displayHost(String bindAddress) {
        if (bindAddress == null || bindAddress.isBlank() || "0.0.0.0".equals(bindAddress) || "::".equals(bindAddress)) {
            return "127.0.0.1";
        }
        if (bindAddress.indexOf(':') >= 0) {
            return bindAddress.startsWith("[") ? bindAddress : "[" + bindAddress + "]";
        }
        return bindAddress;
    }

    public static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
