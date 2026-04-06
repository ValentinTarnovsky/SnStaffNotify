package com.sn.staffnotify.util;

import com.sn.staffnotify.database.StaffDatabase;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.UUID;

/**
 * Utility class for staff-related operations.
 * Centralizes staff detection and broadcast logic used by multiple components.
 */
public final class StaffUtil {

    private StaffUtil() {
    }

    /**
     * Checks if a player is staff: either in SnStaffLink's database
     * or has full permissions (*) on the proxy.
     *
     * @param player  the player to check
     * @param staffDb the staff database instance
     * @return true if the player is considered staff
     */
    public static boolean isStaff(Player player, StaffDatabase staffDb) {
        return staffDb.isStaff(player.getUniqueId()) || player.hasPermission("*");
    }

    /**
     * Broadcasts a message to all online staff members.
     *
     * @param proxy       the proxy server instance
     * @param staffDb     the staff database for staff detection
     * @param message     the message component to broadcast
     * @param excludeUuid UUID to exclude from the broadcast, or null
     */
    public static void broadcastToStaff(ProxyServer proxy, StaffDatabase staffDb,
                                        Component message, UUID excludeUuid) {
        for (Player p : proxy.getAllPlayers()) {
            if (excludeUuid != null && p.getUniqueId().equals(excludeUuid)) continue;
            if (isStaff(p, staffDb)) {
                p.sendMessage(message);
            }
        }
    }
}
