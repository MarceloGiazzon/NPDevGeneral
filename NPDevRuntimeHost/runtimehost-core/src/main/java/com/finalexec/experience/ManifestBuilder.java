package com.finalexec.experience;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * FORM P5-T7: the web app manifest ({@code GET /x/<experience>/manifest.webmanifest}) of one resolved experience.
 * Pure and free of Spring; {@code ExperienceController} is the HTTP layer.
 *
 * <p>Only an experience whose {@code device} is {@code mobile} or {@code both} (the schema default) is installable:
 * a {@code web} experience yields {@link Optional#empty()} (the route answers 404). The colours are the resolved
 * {@code themeColor} / {@code backgroundColor} the generator wrote into {@code experiences.json} (the experience's own
 * {@code theme} object, else the shell's default accent and background); a missing or malformed value falls back to the
 * same shell defaults. Icons are the two PNGs the generator wrote under {@code static/x/icons/}; the same square is
 * listed for the {@code any} and {@code maskable} purposes because it is full-bleed.
 *
 * <p>There is no service worker and no offline support: this only makes the experience installable (MOB-2 owns offline).
 */
public final class ManifestBuilder {

    public static final String DEFAULT_THEME_COLOR = "#0b5fff";
    public static final String DEFAULT_BACKGROUND_COLOR = "#ffffff";
    public static final int[] ICON_SIZES = {192, 512};

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final int SHORT_NAME_MAX = 12;

    private ManifestBuilder() {
    }

    /** True when the experience may be installed (device mobile or both; absent means both). */
    public static boolean installable(JsonNode experience) {
        if (experience == null || experience.path("name").asText("").isBlank()) {
            return false;
        }
        String device = experience.path("device").asText("both");
        return "mobile".equals(device) || "both".equals(device);
    }

    /** The manifest as an ordered map ready for JSON serialisation, or empty when the experience is not installable. */
    public static Optional<Map<String, Object>> build(JsonNode experience) {
        if (!installable(experience)) {
            return Optional.empty();
        }
        String name = experience.path("name").asText();
        String title = experience.path("title").asText("");
        String base = "/x/" + name + "/";
        String displayName = title.isBlank() ? name : title;
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("id", base);
        manifest.put("name", displayName);
        // the identifier when it is short, else a short title, else the identifier cut to length
        manifest.put("short_name", name.length() <= SHORT_NAME_MAX ? name
                : (!title.isBlank() && title.length() <= SHORT_NAME_MAX ? title : name.substring(0, SHORT_NAME_MAX)));
        manifest.put("start_url", base);
        manifest.put("scope", base);
        manifest.put("display", "standalone");
        if ("mobile".equals(experience.path("device").asText("both"))) {
            manifest.put("orientation", "portrait");
        }
        manifest.put("theme_color", color(experience, "themeColor", DEFAULT_THEME_COLOR));
        manifest.put("background_color", color(experience, "backgroundColor", DEFAULT_BACKGROUND_COLOR));
        List<Map<String, Object>> icons = new ArrayList<>();
        for (String purpose : new String[] {"any", "maskable"}) {
            for (int size : ICON_SIZES) {
                Map<String, Object> icon = new LinkedHashMap<>();
                icon.put("src", base + "icon-" + size + ".png");
                icon.put("sizes", size + "x" + size);
                icon.put("type", "image/png");
                icon.put("purpose", purpose);
                icons.add(icon);
            }
        }
        manifest.put("icons", icons);
        return Optional.of(manifest);
    }

    /** The file-name stem the generator used for this experience's icons (ExperienceEmitter.iconStem). */
    public static String iconStem(String experienceName) {
        return experienceName.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String color(JsonNode experience, String key, String fallback) {
        String value = experience.path(key).asText("").trim();
        return HEX.matcher(value).matches() ? value.toLowerCase(java.util.Locale.ROOT) : fallback;
    }
}
