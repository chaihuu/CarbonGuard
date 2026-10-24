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

package xyz.chaihu.carbonguard.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads {@code messages.yml}. Only Adventure 5 APIs are used; messages are parsed
 * with the ampersand legacy serializer, never with the removed component builder.
 */
public final class Messages {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final YamlConfiguration yaml;
    private final String prefix;

    private Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
        this.prefix = yaml.getString("prefix", "&8[&bCarbonGuard&8] &r");
    }

    public static Messages load(JavaPlugin plugin) {
        Path file = plugin.getDataFolder().toPath().resolve("messages.yml");
        if (!Files.exists(file)) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("messages.yml 解析失败: " + file, e);
        }
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
            // The on-disk file is authoritative; defaults are a convenience only.
        }
        return new Messages(yaml);
    }

    public String raw(String key) {
        String value = yaml.getString(key);
        if (value == null) {
            return key;
        }
        return value;
    }

    public Component component(String key, Map<String, String> placeholders) {
        String value = raw(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        value = value.replace("{prefix}", prefix);
        return LEGACY.deserialize(value);
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public String plain(String key, Map<String, String> placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, placeholders));
    }

    public String prefix() {
        return prefix;
    }

    public String rawWithPrefix(String text) {
        return prefix + text;
    }
}
