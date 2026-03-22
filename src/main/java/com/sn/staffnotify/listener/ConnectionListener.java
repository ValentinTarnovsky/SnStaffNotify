package com.sn.staffnotify.listener;

import com.sn.staffnotify.config.ConfigManager;
import com.sn.staffnotify.config.MessagesManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to Velocity connection events and broadcasts staff join, leave,
 * and server-switch notifications to all online staff members.
 */
public final class ConnectionListener {

    private final ProxyServer proxy;
    private final Logger logger;
    private final ConfigManager config;
    private final MessagesManager messages;
    private final Set<UUID> knownStaff = ConcurrentHashMap.newKeySet();

    public ConnectionListener(ProxyServer proxy, Logger logger, ConfigManager config, MessagesManager messages) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.messages = messages;
    }

    /**
     * Handles a player connecting to a backend server.
     * Distinguishes between an initial network join (no previous server)
     * and a server switch (previous server present).
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        // Skip ignored players
        if (config.isIgnored(player.getUniqueId(), player.getUsername())) return;

        // Skip non-staff
        if (!player.hasPermission(config.getStaffPermission())) return;

        if (event.getPreviousServer().isEmpty()) {
            // INITIAL JOIN - player just connected to the network
            knownStaff.add(player.getUniqueId());
            broadcast("messages.join", Map.of(
                    "player", player.getUsername(),
                    "server", serverName
            ), null);
        } else {
            // SERVER SWITCH
            String previousName = event.getPreviousServer().get().getServerInfo().getName();
            broadcast("messages.switch", Map.of(
                    "player", player.getUsername(),
                    "server", serverName,
                    "previous_server", previousName
            ), null);
        }
    }

    /**
     * Handles a player disconnecting from the network.
     * Only broadcasts if the player was tracked as staff.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();

        // Only process if they were tracked as staff
        if (!knownStaff.remove(player.getUniqueId())) return;

        // Skip ignored players
        if (config.isIgnored(player.getUniqueId(), player.getUsername())) return;

        broadcast("messages.leave", Map.of("player", player.getUsername()), player.getUniqueId());
    }

    /**
     * Broadcasts a formatted message to all online players who have the staff permission.
     *
     * @param messageKey   the dot-path key in messages.yml
     * @param placeholders placeholder values to inject into the message
     * @param excludeUuid  UUID to exclude from the broadcast (e.g. the disconnecting player), or null
     */
    private void broadcast(String messageKey, Map<String, String> placeholders, UUID excludeUuid) {
        Component message = messages.getMessage(messageKey, placeholders);

        for (Player p : proxy.getAllPlayers()) {
            if (excludeUuid != null && p.getUniqueId().equals(excludeUuid)) continue;
            if (p.hasPermission(config.getStaffPermission())) {
                p.sendMessage(message);
            }
        }
    }
}
