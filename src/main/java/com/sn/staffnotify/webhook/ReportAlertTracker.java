package com.sn.staffnotify.webhook;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks recent report counts per target player for the repeated-report alert.
 * <p>
 * Reports are stored as timestamps in a per-target deque; expired entries are
 * trimmed lazily on access so memory stays bounded by the active window.
 * A separate cooldown map prevents the same target from repeatedly pinging staff.
 */
public final class ReportAlertTracker {

    private final Map<String, Deque<Long>> reports = new ConcurrentHashMap<>();
    private final Map<String, Long> alertCooldowns = new ConcurrentHashMap<>();

    /**
     * Records a new report for the given target and returns the number of
     * reports currently within the window.
     *
     * @param target       target player name (case-insensitive)
     * @param windowMillis time window in milliseconds; 0 means "no trimming"
     * @return the current report count for the target within the window
     */
    public int recordAndCount(String target, long windowMillis) {
        if (target == null || target.isEmpty()) return 0;
        String key = target.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        Deque<Long> deque = reports.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(now);
            if (windowMillis > 0) {
                long cutoff = now - windowMillis;
                while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
                    deque.pollFirst();
                }
            }
            return deque.size();
        }
    }

    /**
     * Attempts to claim the alert cooldown slot for the given target.
     * Returns {@code true} and arms the cooldown if no alert is currently
     * cooling down; returns {@code false} otherwise.
     *
     * @param target         target player name (case-insensitive)
     * @param cooldownMillis cooldown in milliseconds; 0 disables the cooldown
     */
    public boolean tryArmCooldown(String target, long cooldownMillis) {
        if (target == null || target.isEmpty()) return false;
        String key = target.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (cooldownMillis <= 0) {
            return true;
        }

        // Opportunistic cleanup of stale cooldown entries to keep the map bounded.
        Iterator<Map.Entry<String, Long>> it = alertCooldowns.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }

        Long previousExpiry = alertCooldowns.putIfAbsent(key, now + cooldownMillis);
        if (previousExpiry == null) {
            return true;
        }
        if (previousExpiry <= now) {
            alertCooldowns.put(key, now + cooldownMillis);
            return true;
        }
        return false;
    }

    /**
     * Clears all tracked state. Called on plugin shutdown to release memory.
     */
    public void clear() {
        reports.clear();
        alertCooldowns.clear();
    }
}
