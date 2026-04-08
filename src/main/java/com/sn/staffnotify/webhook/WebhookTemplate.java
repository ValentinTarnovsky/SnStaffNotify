package com.sn.staffnotify.webhook;

import java.util.List;

/**
 * Immutable configuration template for a Discord webhook payload.
 * Populated once from config.yml and reused for every notification.
 * Placeholder substitution happens at send time inside {@link WebhookManager}.
 */
public record WebhookTemplate(
        boolean enabled,
        String url,
        String webhookUsername,
        String webhookAvatar,
        String content,
        EmbedTemplate embed
) {

    public record EmbedTemplate(
            boolean enabled,
            String title,
            String titleUrl,
            String description,
            int color,
            AuthorTemplate author,
            String thumbnailUrl,
            String imageUrl,
            FooterTemplate footer,
            boolean timestamp,
            List<FieldTemplate> fields
    ) {}

    public record AuthorTemplate(String name, String iconUrl, String url) {}

    public record FooterTemplate(String text, String iconUrl) {}

    public record FieldTemplate(String name, String value, boolean inline) {}
}
