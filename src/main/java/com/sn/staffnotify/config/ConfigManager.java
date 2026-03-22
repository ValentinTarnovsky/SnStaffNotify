package com.sn.staffnotify.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Manages the plugin's config.yml using SnakeYAML.
 * Handles config version checking, default generation, and reloading.
 */
public final class ConfigManager {

    private static final String FILE_NAME = "config.yml";
    private static final int CURRENT_CONFIG_VERSION = 1;

    private final Logger logger;
    private final Path dataDir;
    private final Path configPath;

    private volatile boolean debug;
    private volatile String staffPermission;
    private volatile String commandPermission;
    private volatile Set<String> ignoredUuids;
    private volatile Set<String> ignoredNames;

    public ConfigManager(Logger logger, Path dataDir) {
        this.logger = logger;
        this.dataDir = dataDir;
        this.configPath = dataDir.resolve(FILE_NAME);
    }

    /**
     * Loads or creates the configuration file and parses all values.
     */
    public void load() {
        try {
            if (Files.notExists(dataDir)) {
                Files.createDirectories(dataDir);
            }
            if (Files.notExists(configPath)) {
                copyDefault();
            }

            Map<String, Object> config = loadYaml(configPath);
            checkConfigVersion(config);

            // Re-load after potential version migration
            config = loadYaml(configPath);
            parseValues(config);

        } catch (IOException e) {
            logger.error("Failed to load config.yml, using defaults.", e);
            applyDefaults();
        }
    }

    /**
     * Reloads the configuration from disk.
     */
    public void reload() {
        load();
    }

    @SuppressWarnings("unchecked")
    private void parseValues(Map<String, Object> config) {
        this.debug = Boolean.TRUE.equals(config.get("debug"));

        Object staffPermObj = config.get("staff-permission");
        this.staffPermission = staffPermObj != null ? staffPermObj.toString() : "nookure.staff.staffchat";

        Object cmdPermObj = config.get("command-permission");
        this.commandPermission = cmdPermObj != null ? cmdPermObj.toString() : "snstaffnotify.admin";

        // Parse ignored-players section
        Object ignoredObj = config.get("ignored-players");
        if (ignoredObj instanceof Map<?, ?> ignoredMap) {
            Object uuidsObj = ignoredMap.get("uuids");
            if (uuidsObj instanceof List<?> list) {
                Set<String> uuids = new HashSet<>();
                for (Object o : list) {
                    if (o != null) {
                        uuids.add(o.toString().toLowerCase(Locale.ROOT));
                    }
                }
                this.ignoredUuids = Collections.unmodifiableSet(uuids);
            } else {
                this.ignoredUuids = Set.of();
            }

            Object namesObj = ignoredMap.get("names");
            if (namesObj instanceof List<?> list) {
                Set<String> names = new HashSet<>();
                for (Object o : list) {
                    if (o != null) {
                        names.add(o.toString().toLowerCase(Locale.ROOT));
                    }
                }
                this.ignoredNames = Collections.unmodifiableSet(names);
            } else {
                this.ignoredNames = Set.of();
            }
        } else {
            this.ignoredUuids = Set.of();
            this.ignoredNames = Set.of();
        }

        if (debug) {
            logger.info("[DEBUG] Loaded config: staffPermission={}, commandPermission={}, ignoredUuids={}, ignoredNames={}",
                    staffPermission, commandPermission, ignoredUuids.size(), ignoredNames.size());
        }
    }

    private void applyDefaults() {
        this.debug = false;
        this.staffPermission = "nookure.staff.staffchat";
        this.commandPermission = "snstaffnotify.admin";
        this.ignoredUuids = Set.of();
        this.ignoredNames = Set.of();
    }

    private void checkConfigVersion(Map<String, Object> config) throws IOException {
        Object versionObj = config.get("config-version");
        int version = versionObj instanceof Number n ? n.intValue() : 0;

        if (version < CURRENT_CONFIG_VERSION) {
            logger.warn("Config is outdated (version {} < {}). Regenerating...", version, CURRENT_CONFIG_VERSION);
            String backupName = "old-config-v" + version + "-" + System.currentTimeMillis() + ".yml";
            Path backupPath = dataDir.resolve(backupName);
            Files.move(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            logger.warn("Old config renamed to {}", backupPath.getFileName());
            copyDefault();
        }
    }

    private void copyDefault() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + FILE_NAME)) {
            if (in != null) {
                Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                logger.error("Default config.yml not found in resources!");
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

    /**
     * Checks whether the given player is ignored by UUID or name.
     *
     * @param uuid the player's UUID
     * @param name the player's name
     * @return true if the player is in either ignore list
     */
    public boolean isIgnored(UUID uuid, String name) {
        if (uuid != null && ignoredUuids.contains(uuid.toString().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (name != null && ignoredNames.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return false;
    }

    /**
     * Logs a debug message if debug mode is enabled.
     */
    public void debug(String message, Object... args) {
        if (debug) {
            logger.info("[DEBUG] " + message, args);
        }
    }

    public boolean isDebug() { return debug; }
    public String getStaffPermission() { return staffPermission; }
    public String getCommandPermission() { return commandPermission; }
    public Set<String> getIgnoredUuids() { return ignoredUuids; }
    public Set<String> getIgnoredNames() { return ignoredNames; }
}
