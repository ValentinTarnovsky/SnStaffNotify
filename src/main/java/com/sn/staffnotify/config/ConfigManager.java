package com.sn.staffnotify.config;

import com.sn.staffnotify.webhook.ReportAlertConfig;
import com.sn.staffnotify.webhook.WebhookTemplate;
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
    private static final int CURRENT_CONFIG_VERSION = 5;

    private final Logger logger;
    private final Path dataDir;
    private final Path configPath;

    private volatile boolean debug;
    private volatile String commandPermission;
    private volatile Set<String> ignoredUuids;
    private volatile Set<String> ignoredNames;

    // Database settings
    private volatile String dbHost;
    private volatile int dbPort;
    private volatile String dbDatabase;
    private volatile String dbUsername;
    private volatile String dbPassword;
    private volatile String dbTable;
    private volatile int cacheSeconds;

    // Webhook templates (fully customizable)
    private volatile WebhookTemplate helpopWebhook;
    private volatile WebhookTemplate reportWebhook;
    private volatile ReportAlertConfig reportAlert;

    // Cooldown settings (seconds)
    private volatile int cooldownHelpop;
    private volatile int cooldownReport;

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

        Object cmdPermObj = config.get("command-permission");
        this.commandPermission = cmdPermObj != null ? cmdPermObj.toString() : "snstaffnotify.admin";

        // Parse database section
        Object dbObj = config.get("database");
        if (dbObj instanceof Map<?, ?> dbMap) {
            this.dbHost = getStringOrDefault(dbMap, "host", "localhost");
            this.dbPort = getIntOrDefault(dbMap, "port", 3306);
            this.dbDatabase = getStringOrDefault(dbMap, "database", "minecraft");
            this.dbUsername = getStringOrDefault(dbMap, "username", "root");
            this.dbPassword = getStringOrDefault(dbMap, "password", "");
            this.dbTable = getStringOrDefault(dbMap, "table", "stafflink_users");
            this.cacheSeconds = getIntOrDefault(dbMap, "cache-seconds", 60);
        } else {
            applyDatabaseDefaults();
        }

        // Parse ignored-players section
        Object ignoredObj = config.get("ignored-players");
        if (ignoredObj instanceof Map<?, ?> ignoredMap) {
            this.ignoredUuids = parseStringSet(ignoredMap.get("uuids"));
            this.ignoredNames = parseStringSet(ignoredMap.get("names"));
        } else {
            this.ignoredUuids = Set.of();
            this.ignoredNames = Set.of();
        }

        // Parse webhooks section
        Object webhooksObj = config.get("webhooks");
        if (webhooksObj instanceof Map<?, ?> webhooksMap) {
            this.helpopWebhook = parseWebhook(webhooksMap.get("helpop"), 0xFFA500);
            this.reportWebhook = parseWebhook(webhooksMap.get("report"), 0xFF4444);

            Object reportObj = webhooksMap.get("report");
            this.reportAlert = (reportObj instanceof Map<?, ?> reportMap)
                    ? parseReportAlert(reportMap.get("alert"))
                    : ReportAlertConfig.disabled();
        } else {
            applyWebhookDefaults();
        }

        // Parse cooldowns section
        Object cooldownsObj = config.get("cooldowns");
        if (cooldownsObj instanceof Map<?, ?> cooldownsMap) {
            this.cooldownHelpop = getIntOrDefault(cooldownsMap, "helpop", 30);
            this.cooldownReport = getIntOrDefault(cooldownsMap, "report", 60);
        } else {
            this.cooldownHelpop = 30;
            this.cooldownReport = 60;
        }

        if (debug) {
            logger.info("[DEBUG] Loaded config: db={}:{}/{}, table={}, cache={}s, ignoredUuids={}, ignoredNames={}",
                    dbHost, dbPort, dbDatabase, dbTable, cacheSeconds,
                    ignoredUuids.size(), ignoredNames.size());
            logger.info("[DEBUG] Webhooks: helpop={}, report={}",
                    helpopWebhook.enabled(), reportWebhook.enabled());
            logger.info("[DEBUG] Cooldowns: helpop={}s, report={}s", cooldownHelpop, cooldownReport);
        }
    }

    /**
     * Parses a single webhook section into an immutable {@link WebhookTemplate}.
     * Missing fields fall back to safe defaults so partial configs don't break.
     */
    private WebhookTemplate parseWebhook(Object webhookObj, int defaultColor) {
        if (!(webhookObj instanceof Map<?, ?> map)) {
            return emptyWebhook(defaultColor);
        }

        boolean enabled = Boolean.TRUE.equals(map.get("enabled"));
        String url = getStringOrDefault(map, "url", "");
        String webhookUsername = getStringOrDefault(map, "webhook-username", "");
        String webhookAvatar = getStringOrDefault(map, "webhook-avatar", "");
        String content = getStringOrDefault(map, "content", "");

        WebhookTemplate.EmbedTemplate embed = parseEmbed(map.get("embed"), defaultColor);
        return new WebhookTemplate(enabled, url, webhookUsername, webhookAvatar, content, embed);
    }

    @SuppressWarnings("unchecked")
    private WebhookTemplate.EmbedTemplate parseEmbed(Object embedObj, int defaultColor) {
        if (!(embedObj instanceof Map<?, ?> map)) {
            return emptyEmbed(defaultColor);
        }

        boolean enabled = !Boolean.FALSE.equals(map.get("enabled"));
        String title = getStringOrDefault(map, "title", "");
        String titleUrl = getStringOrDefault(map, "title-url", "");
        String description = getStringOrDefault(map, "description", "");
        int color = parseColor(map.get("color"), defaultColor);

        // Author subsection
        WebhookTemplate.AuthorTemplate author;
        Object authorObj = map.get("author");
        if (authorObj instanceof Map<?, ?> authorMap) {
            author = new WebhookTemplate.AuthorTemplate(
                    getStringOrDefault(authorMap, "name", ""),
                    getStringOrDefault(authorMap, "icon-url", ""),
                    getStringOrDefault(authorMap, "url", "")
            );
        } else {
            author = new WebhookTemplate.AuthorTemplate("", "", "");
        }

        String thumbnailUrl = getStringOrDefault(map, "thumbnail-url", "");
        String imageUrl = getStringOrDefault(map, "image-url", "");

        // Footer subsection
        WebhookTemplate.FooterTemplate footer;
        Object footerObj = map.get("footer");
        if (footerObj instanceof Map<?, ?> footerMap) {
            footer = new WebhookTemplate.FooterTemplate(
                    getStringOrDefault(footerMap, "text", ""),
                    getStringOrDefault(footerMap, "icon-url", "")
            );
        } else {
            footer = new WebhookTemplate.FooterTemplate("", "");
        }

        boolean timestamp = Boolean.TRUE.equals(map.get("timestamp"));

        // Fields list — arbitrary length, order preserved
        List<WebhookTemplate.FieldTemplate> fields = new ArrayList<>();
        Object fieldsObj = map.get("fields");
        if (fieldsObj instanceof List<?> fieldsList) {
            for (Object entry : fieldsList) {
                if (entry instanceof Map<?, ?> fieldMap) {
                    fields.add(new WebhookTemplate.FieldTemplate(
                            getStringOrDefault(fieldMap, "name", ""),
                            getStringOrDefault(fieldMap, "value", ""),
                            !Boolean.FALSE.equals(fieldMap.get("inline"))
                    ));
                }
            }
        }

        return new WebhookTemplate.EmbedTemplate(
                enabled, title, titleUrl, description, color,
                author, thumbnailUrl, imageUrl, footer, timestamp,
                Collections.unmodifiableList(fields)
        );
    }

    /**
     * Parses a color value supporting "#RRGGBB", "0xRRGGBB", or raw integers.
     */
    private int parseColor(Object raw, int fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) return n.intValue();

        String s = raw.toString().trim();
        if (s.isEmpty()) return fallback;

        try {
            if (s.startsWith("#")) {
                return Integer.parseInt(s.substring(1), 16);
            }
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Integer.parseInt(s.substring(2), 16);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            logger.warn("Invalid embed color '{}', using default.", s);
            return fallback;
        }
    }

    private WebhookTemplate emptyWebhook(int defaultColor) {
        return new WebhookTemplate(false, "", "", "", "", emptyEmbed(defaultColor));
    }

    private WebhookTemplate.EmbedTemplate emptyEmbed(int defaultColor) {
        return new WebhookTemplate.EmbedTemplate(
                true, "", "", "", defaultColor,
                new WebhookTemplate.AuthorTemplate("", "", ""),
                "", "",
                new WebhookTemplate.FooterTemplate("", ""),
                false,
                List.of()
        );
    }

    /**
     * Parses the {@code alert} section nested under the report webhook.
     * Returns a disabled configuration if the section is missing.
     */
    private ReportAlertConfig parseReportAlert(Object alertObj) {
        if (!(alertObj instanceof Map<?, ?> map)) {
            return ReportAlertConfig.disabled();
        }
        boolean enabled = Boolean.TRUE.equals(map.get("enabled"));
        int threshold = getIntOrDefault(map, "threshold", 3);
        int windowMinutes = getIntOrDefault(map, "window-minutes", 15);
        String roleId = getStringOrDefault(map, "role-id", "");
        String message = getStringOrDefault(map, "message", "");
        int cooldownSeconds = getIntOrDefault(map, "cooldown-seconds", 300);
        return new ReportAlertConfig(enabled, threshold, windowMinutes, roleId, message, cooldownSeconds);
    }

    private Set<String> parseStringSet(Object listObj) {
        if (!(listObj instanceof List<?> list)) return Set.of();
        Set<String> result = new HashSet<>();
        for (Object o : list) {
            if (o != null) {
                result.add(o.toString().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private String getStringOrDefault(Map<?, ?> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private int getIntOrDefault(Map<?, ?> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val != null) {
            try { return Integer.parseInt(val.toString()); }
            catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }

    private void applyDefaults() {
        this.debug = false;
        this.commandPermission = "snstaffnotify.admin";
        this.ignoredUuids = Set.of();
        this.ignoredNames = Set.of();
        applyDatabaseDefaults();
        applyWebhookDefaults();
        this.cooldownHelpop = 30;
        this.cooldownReport = 60;
    }

    private void applyDatabaseDefaults() {
        this.dbHost = "localhost";
        this.dbPort = 3306;
        this.dbDatabase = "minecraft";
        this.dbUsername = "root";
        this.dbPassword = "";
        this.dbTable = "stafflink_users";
        this.cacheSeconds = 60;
    }

    private void applyWebhookDefaults() {
        this.helpopWebhook = emptyWebhook(0xFFA500);
        this.reportWebhook = emptyWebhook(0xFF4444);
        this.reportAlert = ReportAlertConfig.disabled();
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
     */
    public boolean isIgnored(UUID uuid, String name) {
        if (uuid != null && ignoredUuids.contains(uuid.toString().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return name != null && ignoredNames.contains(name.toLowerCase(Locale.ROOT));
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
    public String getCommandPermission() { return commandPermission; }
    public Set<String> getIgnoredUuids() { return ignoredUuids; }
    public Set<String> getIgnoredNames() { return ignoredNames; }

    public String getDbHost() { return dbHost; }
    public int getDbPort() { return dbPort; }
    public String getDbDatabase() { return dbDatabase; }
    public String getDbUsername() { return dbUsername; }
    public String getDbPassword() { return dbPassword; }
    public String getDbTable() { return dbTable; }
    public int getCacheSeconds() { return cacheSeconds; }

    public WebhookTemplate getHelpopWebhook() { return helpopWebhook; }
    public WebhookTemplate getReportWebhook() { return reportWebhook; }
    public ReportAlertConfig getReportAlert() { return reportAlert; }

    public int getCooldownHelpop() { return cooldownHelpop; }
    public int getCooldownReport() { return cooldownReport; }
}
