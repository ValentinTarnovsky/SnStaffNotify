package com.sn.staffnotify.webhook;

import com.sn.staffnotify.config.ConfigManager;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages Discord webhook notifications for HelpOp and Report commands.
 * Builds fully customizable payloads from {@link WebhookTemplate} config
 * and sends them asynchronously to avoid blocking proxy threads.
 */
public final class WebhookManager {

    /** mc-heads.net endpoints — free, no auth, supports both premium and offline skins by username. */
    private static final String SKIN_FACE_URL = "https://mc-heads.net/avatar/%s/128";
    private static final String SKIN_HEAD_URL = "https://mc-heads.net/head/%s/128";
    private static final String SKIN_BODY_URL = "https://mc-heads.net/body/%s/256";

    private final Logger logger;
    private final ConfigManager config;
    private final HttpClient httpClient;
    private final ReportAlertTracker alertTracker;

    public WebhookManager(Logger logger, ConfigManager config) {
        this.logger = logger;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.alertTracker = new ReportAlertTracker();
    }

    /**
     * Sends a HelpOp notification to the configured Discord webhook.
     */
    public void sendHelpOp(String player, UUID uuid, String server, String message) {
        WebhookTemplate template = config.getHelpopWebhook();
        if (!template.enabled() || template.url() == null || template.url().isBlank()) return;

        Map<String, String> placeholders = commonPlaceholders(player, uuid, server);
        placeholders.put("message", message);

        dispatch(template, placeholders, "helpop");
    }

    /**
     * Sends a Report notification to the configured Discord webhook.
     * If the alert threshold is tripped, also sends a follow-up role ping.
     */
    public void sendReport(String reporter, UUID uuid, String server, String target, String reason) {
        WebhookTemplate template = config.getReportWebhook();
        if (!template.enabled() || template.url() == null || template.url().isBlank()) return;

        Map<String, String> placeholders = commonPlaceholders(reporter, uuid, server);
        placeholders.put("target", target);
        placeholders.put("reason", reason);

        dispatch(template, placeholders, "report");
        checkReportAlert(template.url(), placeholders, target);
    }

    /**
     * Records the report in the tracker and, if the configured threshold is
     * reached, posts an extra message to the report webhook URL with a role ping.
     */
    private void checkReportAlert(String webhookUrl, Map<String, String> placeholders, String target) {
        ReportAlertConfig alert = config.getReportAlert();
        if (!alert.enabled() || alert.threshold() <= 0) return;

        long windowMillis = alert.windowMinutes() > 0 ? alert.windowMinutes() * 60_000L : 0L;
        int count = alertTracker.recordAndCount(target, windowMillis);
        if (count < alert.threshold()) return;

        long cooldownMillis = alert.cooldownSeconds() > 0 ? alert.cooldownSeconds() * 1000L : 0L;
        if (!alertTracker.tryArmCooldown(target, cooldownMillis)) return;

        sendReportAlert(webhookUrl, alert, placeholders, count);
    }

    /**
     * Builds and dispatches the repeated-report alert payload with role ping.
     */
    private void sendReportAlert(String webhookUrl, ReportAlertConfig alert,
                                 Map<String, String> basePlaceholders, int count) {
        String roleId = alert.roleId() == null ? "" : alert.roleId().trim();
        String roleMention = roleId.isEmpty() ? "" : "<@&" + roleId + ">";

        Map<String, String> alertPlaceholders = new HashMap<>(basePlaceholders);
        alertPlaceholders.put("count", Integer.toString(count));
        alertPlaceholders.put("threshold", Integer.toString(alert.threshold()));
        alertPlaceholders.put("window", Integer.toString(alert.windowMinutes()));
        alertPlaceholders.put("role", roleMention);

        String message = replace(alert.message(), alertPlaceholders);
        if (message.isEmpty()) return;

        StringBuilder json = new StringBuilder(128);
        json.append("{\"content\":\"").append(escapeJson(message)).append('"');

        // Only allow role mentions — prevents @everyone / user ping abuse via the message field.
        if (!roleId.isEmpty()) {
            json.append(",\"allowed_mentions\":{\"parse\":[\"roles\"],\"roles\":[\"")
                    .append(escapeJson(roleId))
                    .append("\"]}");
        } else {
            json.append(",\"allowed_mentions\":{\"parse\":[]}");
        }
        json.append('}');

        sendWebhook(webhookUrl, json.toString(), "report-alert");
    }

    /**
     * Builds the base placeholder map shared by every webhook type.
     */
    private Map<String, String> commonPlaceholders(String player, UUID uuid, String server) {
        Map<String, String> map = new HashMap<>();
        map.put("username", player);
        map.put("uuid", uuid != null ? uuid.toString() : "");
        map.put("server", server);
        map.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        String encoded = URLEncoder.encode(player, StandardCharsets.UTF_8);
        map.put("skin_face", String.format(SKIN_FACE_URL, encoded));
        map.put("skin_head", String.format(SKIN_HEAD_URL, encoded));
        map.put("skin_body", String.format(SKIN_BODY_URL, encoded));
        return map;
    }

    /**
     * Builds the final JSON payload and sends it to Discord asynchronously.
     */
    private void dispatch(WebhookTemplate template, Map<String, String> placeholders, String type) {
        String json = buildPayload(template, placeholders);
        sendWebhook(template.url(), json, type);
    }

    /**
     * Builds the top-level Discord webhook JSON payload from the template.
     * All string fields are placeholder-substituted and JSON-escaped.
     */
    private String buildPayload(WebhookTemplate template, Map<String, String> placeholders) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');

        boolean first = true;

        // Bot username override
        String webhookUsername = replace(template.webhookUsername(), placeholders);
        if (!webhookUsername.isEmpty()) {
            first = appendKey(json, first, "username", webhookUsername);
        }

        // Bot avatar override
        String webhookAvatar = replace(template.webhookAvatar(), placeholders);
        if (!webhookAvatar.isEmpty()) {
            first = appendKey(json, first, "avatar_url", webhookAvatar);
        }

        // Plain content above the embed
        String content = replace(template.content(), placeholders);
        if (!content.isEmpty()) {
            first = appendKey(json, first, "content", content);
        }

        // Embed array — included only if the embed is enabled
        WebhookTemplate.EmbedTemplate embed = template.embed();
        if (embed.enabled()) {
            String embedJson = buildEmbed(embed, placeholders);
            if (!embedJson.isEmpty()) {
                if (!first) json.append(',');
                json.append("\"embeds\":[").append(embedJson).append(']');
                first = false;
            }
        }

        // If absolutely nothing was added, fall back to an empty content field
        // so Discord doesn't reject the request.
        if (first) {
            json.append("\"content\":\"\"");
        }

        json.append('}');
        return json.toString();
    }

    /**
     * Builds a single embed object from the template. Returns empty string if the
     * embed would be completely empty (no content fields set).
     */
    private String buildEmbed(WebhookTemplate.EmbedTemplate embed, Map<String, String> placeholders) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        boolean first = true;

        String title = replace(embed.title(), placeholders);
        if (!title.isEmpty()) {
            first = appendKey(json, first, "title", title);
        }

        String titleUrl = replace(embed.titleUrl(), placeholders);
        if (!titleUrl.isEmpty()) {
            first = appendKey(json, first, "url", titleUrl);
        }

        String description = replace(embed.description(), placeholders);
        if (!description.isEmpty()) {
            first = appendKey(json, first, "description", description);
        }

        // Color is always included (Discord defaults to grey otherwise)
        if (!first) json.append(',');
        json.append("\"color\":").append(embed.color() & 0xFFFFFF);
        first = false;

        // Author section
        WebhookTemplate.AuthorTemplate author = embed.author();
        String authorName = replace(author.name(), placeholders);
        if (!authorName.isEmpty()) {
            json.append(",\"author\":{\"name\":\"").append(escapeJson(authorName)).append('"');
            String authorIcon = replace(author.iconUrl(), placeholders);
            if (!authorIcon.isEmpty()) {
                json.append(",\"icon_url\":\"").append(escapeJson(authorIcon)).append('"');
            }
            String authorUrl = replace(author.url(), placeholders);
            if (!authorUrl.isEmpty()) {
                json.append(",\"url\":\"").append(escapeJson(authorUrl)).append('"');
            }
            json.append('}');
        }

        // Thumbnail
        String thumbnailUrl = replace(embed.thumbnailUrl(), placeholders);
        if (!thumbnailUrl.isEmpty()) {
            json.append(",\"thumbnail\":{\"url\":\"").append(escapeJson(thumbnailUrl)).append("\"}");
        }

        // Large image
        String imageUrl = replace(embed.imageUrl(), placeholders);
        if (!imageUrl.isEmpty()) {
            json.append(",\"image\":{\"url\":\"").append(escapeJson(imageUrl)).append("\"}");
        }

        // Footer
        WebhookTemplate.FooterTemplate footer = embed.footer();
        String footerText = replace(footer.text(), placeholders);
        if (!footerText.isEmpty()) {
            json.append(",\"footer\":{\"text\":\"").append(escapeJson(footerText)).append('"');
            String footerIcon = replace(footer.iconUrl(), placeholders);
            if (!footerIcon.isEmpty()) {
                json.append(",\"icon_url\":\"").append(escapeJson(footerIcon)).append('"');
            }
            json.append('}');
        }

        // Automatic timestamp
        if (embed.timestamp()) {
            json.append(",\"timestamp\":\"")
                    .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                    .append('"');
        }

        // Custom fields
        if (!embed.fields().isEmpty()) {
            json.append(",\"fields\":[");
            boolean firstField = true;
            for (WebhookTemplate.FieldTemplate field : embed.fields()) {
                String name = replace(field.name(), placeholders);
                String value = replace(field.value(), placeholders);
                if (name.isEmpty() && value.isEmpty()) continue;

                if (!firstField) json.append(',');
                json.append("{\"name\":\"").append(escapeJson(name))
                        .append("\",\"value\":\"").append(escapeJson(value))
                        .append("\",\"inline\":").append(field.inline()).append('}');
                firstField = false;
            }
            json.append(']');
        }

        json.append('}');
        return json.toString();
    }

    /**
     * Appends a string key/value to a JSON object builder.
     */
    private boolean appendKey(StringBuilder json, boolean first, String key, String value) {
        if (!first) json.append(',');
        json.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
        return false;
    }

    /**
     * Substitutes {placeholder} tokens in a template string. Never returns null.
     */
    private String replace(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty()) return "";
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String token = "{" + entry.getKey() + "}";
            if (result.contains(token)) {
                result = result.replace(token, entry.getValue() != null ? entry.getValue() : "");
            }
        }
        return result;
    }

    /**
     * Sends a JSON payload to a Discord webhook URL asynchronously.
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
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Closes resources and clears the in-memory alert tracker.
     */
    public void close() {
        // HttpClient doesn't require explicit close in Java 21
        alertTracker.clear();
    }
}
