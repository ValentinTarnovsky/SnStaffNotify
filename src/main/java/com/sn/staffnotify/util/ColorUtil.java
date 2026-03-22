package com.sn.staffnotify.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Utility class for translating color codes in strings.
 * Supports legacy ampersand codes ({@code &a}) and HEX colors ({@code &#RRGGBB}).
 */
public final class ColorUtil {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .build();

    private ColorUtil() {
    }

    /**
     * Translates a string with {@code &a} and {@code &#RRGGBB} color codes
     * into an Adventure {@link Component}.
     *
     * @param text the raw string with color codes
     * @return the translated component, or empty if text is null/empty
     */
    public static Component translate(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        return LEGACY_SERIALIZER.deserialize(text);
    }
}
