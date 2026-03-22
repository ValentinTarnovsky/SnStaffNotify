package com.sn.staffnotify.config;

import com.sn.staffnotify.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the plugin's messages.yml using SnakeYAML.
 * Supports dot-path navigation, placeholder replacement, and color translation.
 */
public final class MessagesManager {

    private static final String FILE_NAME = "messages.yml";
    private static final int CURRENT_CONFIG_VERSION = 1;

    private final Logger logger;
    private final Path dataDir;
    private final Path messagesPath;

    private volatile Map<String, Object> messages;
    private volatile String prefix;

    public MessagesManager(Logger logger, Path dataDir) {
        this.logger = logger;
        this.dataDir = dataDir;
        this.messagesPath = dataDir.resolve(FILE_NAME);
        this.prefix = "";
    }

    /**
     * Loads or creates the messages file and caches the prefix.
     */
    public void load() {
        try {
            if (Files.notExists(dataDir)) {
                Files.createDirectories(dataDir);
            }
            if (Files.notExists(messagesPath)) {
                copyDefault();
            }

            this.messages = loadYaml(messagesPath);
            checkConfigVersion();

            // Re-load after potential version migration
            this.messages = loadYaml(messagesPath);
            String rawPrefix = getRawMessage("prefix");
            this.prefix = rawPrefix != null ? rawPrefix : "";

        } catch (IOException e) {
            logger.error("Failed to load messages.yml.", e);
            this.messages = new HashMap<>();
            this.prefix = "";
        }
    }

    /**
     * Reloads the messages from disk.
     */
    public void reload() {
        load();
    }

    /**
     * Gets a translated message component with prefix resolved.
     *
     * @param key the message key (supports dot-path, e.g. "help.header")
     * @return the translated Adventure component
     */
    public Component getMessage(String key) {
        return getMessage(key, Map.of());
    }

    /**
     * Gets a translated message component with placeholders and prefix resolved.
     *
     * @param key          the message key
     * @param placeholders map of placeholder names to values (without braces)
     * @return the translated Adventure component
     */
    public Component getMessage(String key, Map<String, String> placeholders) {
        String raw = getRawMessage(key);
        if (raw == null || raw.isEmpty()) {
            logger.warn("Message key '{}' not found in messages.yml", key);
            return Component.empty();
        }

        // Replace {prefix} first
        raw = raw.replace("{prefix}", prefix);

        // Replace custom placeholders
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            raw = raw.replace(placeholder, value);
        }

        return ColorUtil.translate(raw);
    }

    /**
     * Gets the raw message string without color translation.
     *
     * @param key the message key (supports dot-path)
     * @return the raw string, or null if not found
     */
    public String getRawMessage(String key) {
        if (messages == null) return null;

        Object value = getValue(key);
        if (value instanceof String s) return s;
        return null;
    }

    /**
     * Gets a raw message string, returning a default if not found.
     *
     * @param key          the message key (supports dot-path)
     * @param defaultValue fallback value if key is missing
     * @return the raw string or the default
     */
    public String getRawOrDefault(String key, String defaultValue) {
        String raw = getRawMessage(key);
        return raw != null ? raw : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Object getValue(String key) {
        String[] path = key.split("\\.");
        Object node = messages;

        for (String segment : path) {
            if (!(node instanceof Map)) return null;
            node = ((Map<String, Object>) node).get(segment);
            if (node == null) return null;
        }

        return node;
    }

    private void checkConfigVersion() throws IOException {
        if (messages == null) return;

        Object versionObj = messages.get("config-version");
        int version = versionObj instanceof Number n ? n.intValue() : 0;

        if (version < CURRENT_CONFIG_VERSION) {
            logger.warn("Messages config is outdated (version {} < {}). Regenerating...",
                    version, CURRENT_CONFIG_VERSION);
            String backupName = "old-messages-v" + version + "-" + System.currentTimeMillis() + ".yml";
            Path backupPath = dataDir.resolve(backupName);
            Files.move(messagesPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            logger.warn("Old messages config renamed to {}", backupPath.getFileName());
            copyDefault();
        }
    }

    private void copyDefault() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + FILE_NAME)) {
            if (in != null) {
                Files.copy(in, messagesPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                logger.error("Default messages.yml not found in resources!");
            }
        }
    }

    private Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            Yaml yaml = new Yaml();
            Map<String, Object> loaded = yaml.load(in);
            return loaded != null ? loaded : new HashMap<>();
        }
    }
}
