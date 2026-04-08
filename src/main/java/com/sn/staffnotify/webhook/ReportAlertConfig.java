package com.sn.staffnotify.webhook;

/**
 * Immutable configuration for the repeated-report alert.
 * Controls when and how the webhook pings a Discord role after a player has
 * been reported multiple times within a time window.
 */
public record ReportAlertConfig(
        boolean enabled,
        int threshold,
        int windowMinutes,
        String roleId,
        String message,
        int cooldownSeconds
) {

    /** Disabled configuration used when the section is missing from config.yml. */
    public static ReportAlertConfig disabled() {
        return new ReportAlertConfig(false, 0, 0, "", "", 0);
    }
}
