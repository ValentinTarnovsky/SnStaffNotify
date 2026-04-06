package com.sn.staffnotify.webhook;

import com.sn.staffnotify.config.ConfigManager;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Manages Discord webhook notifications for HelpOp and Report commands.
 * All HTTP requests are sent asynchronously to avoid blocking proxy threads.
 */
public final class WebhookManager {

    private final Logger logger;
    private final ConfigManager config;
    private final HttpClient httpClient;

    public WebhookManager(Logger logger, ConfigManager config) {
        this.logger = logger;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Sends a HelpOp notification to the configured Discord webhook.
     *
     * @param player  the player requesting help
     * @param server  the server the player is on
     * @param message the help message
     */
    public void sendHelpOp(String player, String server, String message) {
        if (!config.isWebhookHelpopEnabled()) return;

        String url = config.getWebhookHelpopUrl();
        if (url == null || url.isBlank()) return;

        String json = buildEmbed(
                "HelpOp Request",
                0xFFA500,
                new String[][]{
                        {"Player", player, "true"},
                        {"Server", server, "true"},
                        {"Message", message, "false"}
                }
        );

        sendWebhook(url, json, "helpop");
    }

    /**
     * Sends a Report notification to the configured Discord webhook.
     *
     * @param reporter the player who submitted the report
     * @param server   the server the reporter is on
     * @param target   the reported player's name
     * @param reason   the reason for the report
     */
    public void sendReport(String reporter, String server, String target, String reason) {
        if (!config.isWebhookReportEnabled()) return;

        String url = config.getWebhookReportUrl();
        if (url == null || url.isBlank()) return;

        String json = buildEmbed(
                "Player Report",
                0xFF4444,
                new String[][]{
                        {"Reporter", reporter, "true"},
                        {"Reported", target, "true"},
                        {"Server", server, "true"},
                        {"Reason", reason, "false"}
                }
        );

        sendWebhook(url, json, "report");
    }

    /**
     * Builds a Discord embed JSON payload.
     *
     * @param title  the embed title
     * @param color  the embed color as integer
     * @param fields array of [name, value, inline] triplets
     * @return the JSON string
     */
    private String buildEmbed(String title, int color, String[][] fields) {
        StringBuilder fieldsJson = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) fieldsJson.append(',');
            fieldsJson.append("{\"name\":\"")
                    .append(escapeJson(fields[i][0]))
                    .append("\",\"value\":\"")
                    .append(escapeJson(fields[i][1]))
                    .append("\",\"inline\":")
                    .append(fields[i][2])
                    .append('}');
        }

        return "{\"embeds\":[{\"title\":\"" + escapeJson(title)
                + "\",\"color\":" + color
                + ",\"fields\":[" + fieldsJson + "]}]}";
    }

    /**
     * Sends a JSON payload to a Discord webhook URL asynchronously.
     *
     * @param url  the webhook URL
     * @param json the JSON body
     * @param type the notification type for logging
     */
    private void sendWebhook(String url, String json, String type) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        int status = response.statusCode();
                        if (status < 200 || status >= 300) {
                            logger.warn("Discord webhook ({}) returned status {}: {}",
                                    type, status, response.body());
                        } else {
                            config.debug("Discord webhook ({}) sent successfully.", type);
                        }
                    })
                    .exceptionally(ex -> {
                        logger.warn("Failed to send Discord webhook ({}).", type, ex);
                        return null;
                    });
        } catch (Exception e) {
            logger.warn("Failed to create webhook request ({}).", type, e);
        }
    }

    /**
     * Escapes special characters for JSON string values.
     */
    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Closes the HTTP client resources.
     */
    public void close() {
        // HttpClient doesn't require explicit close in Java 21
        // but this method exists for lifecycle consistency
    }
}
