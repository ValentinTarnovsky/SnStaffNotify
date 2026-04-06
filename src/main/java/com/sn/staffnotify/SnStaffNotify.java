package com.sn.staffnotify;

import com.google.inject.Inject;
import com.sn.staffnotify.command.HelpOpCommand;
import com.sn.staffnotify.command.ReportCommand;
import com.sn.staffnotify.command.StaffNotifyCommand;
import com.sn.staffnotify.config.ConfigManager;
import com.sn.staffnotify.config.MessagesManager;
import com.sn.staffnotify.database.StaffDatabase;
import com.sn.staffnotify.listener.ConnectionListener;
import com.sn.staffnotify.webhook.WebhookManager;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Main entry point for SnStaffNotify.
 * Staff join/leave notifications for the Velocity proxy.
 */
public final class SnStaffNotify {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private ConfigManager config;
    private MessagesManager messages;
    private StaffDatabase staffDb;
    private WebhookManager webhookManager;

    @Inject
    public SnStaffNotify(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        // Load configuration
        this.config = new ConfigManager(logger, dataDirectory);
        config.load();

        // Load messages
        this.messages = new MessagesManager(logger, dataDirectory);
        messages.load();

        // Connect to database
        this.staffDb = new StaffDatabase(logger, config);
        if (!staffDb.connect()) {
            logger.error("Failed to connect to SnStaffLink database. Staff notifications will not work!");
            logger.error("Check your database settings in config.yml.");
        }

        // Create webhook manager
        this.webhookManager = new WebhookManager(logger, config);

        // Register listener
        proxy.getEventManager().register(this, new ConnectionListener(proxy, logger, config, messages, staffDb));

        // Register commands
        CommandManager cmdMgr = proxy.getCommandManager();
        cmdMgr.register(
                cmdMgr.metaBuilder("staffnotify").aliases("snotify").build(),
                new StaffNotifyCommand(this, config, messages)
        );
        cmdMgr.register(
                cmdMgr.metaBuilder("helpop").build(),
                new HelpOpCommand(proxy, config, messages, staffDb, webhookManager)
        );
        cmdMgr.register(
                cmdMgr.metaBuilder("report").build(),
                new ReportCommand(proxy, config, messages, staffDb, webhookManager)
        );

        logger.info("SnStaffNotify enabled.");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (webhookManager != null) {
            webhookManager.close();
        }
        if (staffDb != null) {
            staffDb.close();
        }
        logger.info("SnStaffNotify disabled.");
    }

    /**
     * Reloads config, messages, and reconnects the database.
     * Called by the reload command.
     */
    public void reload() {
        config.reload();
        messages.reload();

        // Reconnect database with potentially new settings
        if (staffDb != null) {
            staffDb.close();
        }
        this.staffDb = new StaffDatabase(logger, config);
        if (!staffDb.connect()) {
            logger.error("Failed to reconnect to database after reload.");
        }
    }
}
