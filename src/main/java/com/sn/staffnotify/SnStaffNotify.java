package com.sn.staffnotify;

import com.google.inject.Inject;
import com.sn.staffnotify.command.StaffNotifyCommand;
import com.sn.staffnotify.config.ConfigManager;
import com.sn.staffnotify.config.MessagesManager;
import com.sn.staffnotify.listener.ConnectionListener;
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

        // Register listener
        proxy.getEventManager().register(this, new ConnectionListener(proxy, logger, config, messages));

        // Register command
        CommandManager cmdMgr = proxy.getCommandManager();
        cmdMgr.register(
                cmdMgr.metaBuilder("staffnotify").aliases("snotify").build(),
                new StaffNotifyCommand(this, config, messages)
        );

        logger.info("SnStaffNotify enabled.");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        logger.info("SnStaffNotify disabled.");
    }

    /**
     * Reloads config and messages.
     * Called by the reload command.
     */
    public void reload() {
        config.reload();
        messages.reload();
    }
}
