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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command handler for /report.
 * Allows players to report other players to online staff members.
 */
public final class ReportCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final ConfigManager config;
    private final MessagesManager messages;
    private final StaffDatabase staffDb;
    private final WebhookManager webhookManager;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public ReportCommand(ProxyServer proxy, ConfigManager config, MessagesManager messages,
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
            invocation.source().sendMessage(messages.getMessage("report.players-only"));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(messages.getMessage("report.usage"));
            return;
        }

        // Check server
        var currentServer = player.getCurrentServer().orElse(null);
        if (currentServer == null) {
            player.sendMessage(messages.getMessage("report.no-server"));
            return;
        }

        // Check cooldown
        if (isOnCooldown(player.getUniqueId())) {
            player.sendMessage(messages.getMessage("report.cooldown"));
            return;
        }

        String serverName = currentServer.getServerInfo().getName();
        String targetName = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        // Broadcast to online staff
        Component broadcast = messages.getMessage("report.broadcast", Map.of(
                "player", player.getUsername(),
                "target", targetName,
                "server", serverName,
                "reason", reason
        ));
        StaffUtil.broadcastToStaff(proxy, staffDb, broadcast, null);

        // Send Discord webhook
        webhookManager.sendReport(player.getUsername(), player.getUniqueId(), serverName, targetName, reason);

        // Confirm to sender
        player.sendMessage(messages.getMessage("report.sent"));

        // Apply cooldown
        applyCooldown(player.getUniqueId());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("snstaffnotify.report");
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();

        // Tab-complete player names for the first argument
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            List<String> suggestions = new ArrayList<>();
            for (Player p : proxy.getAllPlayers()) {
                if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(p.getUsername());
                }
            }
            Collections.sort(suggestions);
            return CompletableFuture.completedFuture(suggestions);
        }

        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Checks if the player is currently on cooldown.
     */
    private boolean isOnCooldown(UUID uuid) {
        int cooldownSeconds = config.getCooldownReport();
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
        int cooldownSeconds = config.getCooldownReport();
        if (cooldownSeconds <= 0) return;
        cooldowns.put(uuid, System.currentTimeMillis() + (cooldownSeconds * 1000L));
    }
}
