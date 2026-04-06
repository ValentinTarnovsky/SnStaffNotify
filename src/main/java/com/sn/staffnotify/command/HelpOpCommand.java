package com.sn.staffnotify.command;

import com.sn.staffnotify.config.ConfigManager;
import com.sn.staffnotify.config.MessagesManager;
import com.sn.staffnotify.database.StaffDatabase;
import com.sn.staffnotify.util.StaffUtil;
import com.sn.staffnotify.webhook.WebhookManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command handler for /helpop.
 * Allows players to send help requests to online staff members.
 */
public final class HelpOpCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final ConfigManager config;
    private final MessagesManager messages;
    private final StaffDatabase staffDb;
    private final WebhookManager webhookManager;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public HelpOpCommand(ProxyServer proxy, ConfigManager config, MessagesManager messages,
                         StaffDatabase staffDb, WebhookManager webhookManager) {
        this.proxy = proxy;
        this.config = config;
        this.messages = messages;
        this.staffDb = staffDb;
        this.webhookManager = webhookManager;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messages.getMessage("helpop.players-only"));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            player.sendMessage(messages.getMessage("helpop.usage"));
            return;
        }

        // Check server
        var currentServer = player.getCurrentServer().orElse(null);
        if (currentServer == null) {
            player.sendMessage(messages.getMessage("helpop.no-server"));
            return;
        }

        // Check cooldown
        if (isOnCooldown(player.getUniqueId())) {
            player.sendMessage(messages.getMessage("helpop.cooldown"));
            return;
        }

        String serverName = currentServer.getServerInfo().getName();
        String helpMessage = String.join(" ", args);

        // Broadcast to online staff
        Component broadcast = messages.getMessage("helpop.broadcast", Map.of(
                "player", player.getUsername(),
                "server", serverName,
                "message", helpMessage
        ));
        StaffUtil.broadcastToStaff(proxy, staffDb, broadcast, null);

        // Send Discord webhook
        webhookManager.sendHelpOp(player.getUsername(), serverName, helpMessage);

        // Confirm to sender (only if they're not staff, to avoid duplicate)
        if (!StaffUtil.isStaff(player, staffDb)) {
            player.sendMessage(messages.getMessage("helpop.sent"));
        }

        // Apply cooldown
        applyCooldown(player.getUniqueId());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("snstaffnotify.helpop");
    }

    /**
     * Checks if the player is currently on cooldown.
     */
    private boolean isOnCooldown(UUID uuid) {
        int cooldownSeconds = config.getCooldownHelpop();
        if (cooldownSeconds <= 0) return false;

        Long expiry = cooldowns.get(uuid);
        if (expiry == null) return false;

        if (System.currentTimeMillis() < expiry) {
            return true;
        }

        cooldowns.remove(uuid);
        return false;
    }

    /**
     * Applies the cooldown to the player.
     */
    private void applyCooldown(UUID uuid) {
        int cooldownSeconds = config.getCooldownHelpop();
        if (cooldownSeconds <= 0) return;
        cooldowns.put(uuid, System.currentTimeMillis() + (cooldownSeconds * 1000L));
    }
}
