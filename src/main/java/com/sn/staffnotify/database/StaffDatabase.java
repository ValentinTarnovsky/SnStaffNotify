package com.sn.staffnotify.database;

import com.sn.staffnotify.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the MySQL connection to SnStaffLink's database and provides
 * a cached set of staff UUIDs for efficient lookups.
 */
public final class StaffDatabase {

    private final Logger logger;
    private final ConfigManager config;
    private HikariDataSource dataSource;

    private volatile Set<UUID> staffCache = Set.of();
    private volatile long lastRefresh = 0;

    public StaffDatabase(Logger logger, ConfigManager config) {
        this.logger = logger;
        this.config = config;
    }

    /**
     * Initializes the HikariCP connection pool.
     *
     * @return true if the connection was established successfully
     */
    public boolean connect() {
        try {
            HikariConfig hikari = new HikariConfig();
            hikari.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=UTC",
                    config.getDbHost(), config.getDbPort(), config.getDbDatabase()));
            hikari.setUsername(config.getDbUsername());
            hikari.setPassword(config.getDbPassword());
            hikari.setMaximumPoolSize(2);
            hikari.setMinimumIdle(1);
            hikari.setConnectionTimeout(5000);
            hikari.setIdleTimeout(300000);
            hikari.setMaxLifetime(600000);
            hikari.setPoolName("SnStaffNotify-Pool");

            this.dataSource = new HikariDataSource(hikari);

            // Initial cache load
            refreshCache();
            logger.info("Connected to database. Loaded {} staff members.", staffCache.size());
            return true;

        } catch (Exception e) {
            logger.error("Failed to connect to database.", e);
            return false;
        }
    }

    /**
     * Checks whether the given UUID belongs to a staff member.
     * Automatically refreshes the cache if it has expired.
     *
     * @param uuid the player's UUID
     * @return true if the player is in the staff database
     */
    public boolean isStaff(UUID uuid) {
        long now = System.currentTimeMillis();
        int cacheSeconds = config.getCacheSeconds();

        if (cacheSeconds > 0 && (now - lastRefresh) > (cacheSeconds * 1000L)) {
            refreshCache();
        }

        return staffCache.contains(uuid);
    }

    /**
     * Refreshes the staff cache from the database.
     */
    public void refreshCache() {
        if (dataSource == null || dataSource.isClosed()) return;

        String sql = "SELECT uuid FROM " + config.getDbTable();
        Set<UUID> newCache = new HashSet<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String uuidStr = rs.getString("uuid");
                try {
                    newCache.add(UUID.fromString(uuidStr));
                } catch (IllegalArgumentException e) {
                    config.debug("Invalid UUID in database: {}", uuidStr);
                }
            }

            this.staffCache = Collections.unmodifiableSet(newCache);
            this.lastRefresh = System.currentTimeMillis();
            config.debug("Refreshed staff cache: {} members", newCache.size());

        } catch (SQLException e) {
            logger.error("Failed to refresh staff cache from database.", e);
        }
    }

    /**
     * Closes the connection pool.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Database connection pool closed.");
        }
    }
}
