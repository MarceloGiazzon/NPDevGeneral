package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledGuidePage;
import com.npdev.dsl.v1.compiled.CompiledModel;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MOB-1 (M2): the installable-app files every generated UI carries -- a web manifest and its two PNG
 * icons. Name and colours come from what the model already declares (the default GuidePage's
 * {@code theme.logoText} / {@code theme.accent}, else the namespace and a platform blue); nothing here
 * adds a model property. The icons are drawn with Java2D from shapes only (no fonts, so a headless CI
 * box with none still produces them) and are byte-stable for the same input.
 */
final class PwaAssets {

    static final String MANIFEST_PATH = "src/main/resources/static/npdev-manifest.json";
    static final String ICON_192_PATH = "src/main/resources/static/npdev-icon-192.png";
    static final String ICON_512_PATH = "src/main/resources/static/npdev-icon-512.png";

    private static final String DEFAULT_ACCENT = "#2563eb";
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PwaAssets() {
    }

    static String manifestJson(CompiledModel model) {
        String name = appName(model);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", name);
        root.put("short_name", name.length() <= 12 ? name : name.substring(0, 12));
        root.put("start_url", "/npdev-business-ui/index.html");
        root.put("scope", "/");
        root.put("display", "standalone");
        root.put("background_color", "#ffffff");
        root.put("theme_color", accent(model));
        List<Map<String, Object>> icons = new ArrayList<>();
        icons.add(icon("/npdev-icon-192.png", "192x192", "any"));
        icons.add(icon("/npdev-icon-512.png", "512x512", "any maskable"));
        root.put("icons", icons);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        } catch (IOException exception) {
            throw new IllegalStateException("cannot serialize the PWA manifest", exception);
        }
    }

    private static Map<String, Object> icon(String src, String sizes, String purpose) {
        Map<String, Object> icon = new LinkedHashMap<>();
        icon.put("src", src);
        icon.put("sizes", sizes);
        icon.put("type", "image/png");
        icon.put("purpose", purpose);
        return icon;
    }

    /** The default GuidePage's logoText, else the namespace, else a generic name. */
    static String appName(CompiledModel model) {
        CompiledGuidePage page = brandedPage(model, false);
        if (page != null) {
            return page.theme().logoText().trim();
        }
        return model == null || model.getNamespace() == null || model.getNamespace().isBlank()
                ? "NPDev App" : model.getNamespace().trim();
    }

    /** The default GuidePage's accent when it is a #rrggbb colour, else the platform blue. */
    static String accent(CompiledModel model) {
        CompiledGuidePage page = brandedPage(model, true);
        return page == null ? DEFAULT_ACCENT : page.theme().accent().trim();
    }

    private static CompiledGuidePage brandedPage(CompiledModel model, boolean wantAccent) {
        if (model == null) {
            return null;
        }
        CompiledGuidePage fallback = null;
        for (CompiledGuidePage page : model.getGuidePages()) {
            if (page.theme() == null || !hasBranding(page, wantAccent)) {
                continue;
            }
            if (page.isDefault()) {
                return page;
            }
            if (fallback == null) {
                fallback = page;
            }
        }
        return fallback;
    }

    private static boolean hasBranding(CompiledGuidePage page, boolean wantAccent) {
        if (wantAccent) {
            return page.theme().accent() != null && HEX_COLOR.matcher(page.theme().accent().trim()).matches();
        }
        return page.theme().logoText() != null && !page.theme().logoText().isBlank();
    }

    /** A full-bleed accent square with a white ring and dot, inside the maskable safe zone. */
    static byte[] iconPng(int size, String accentHex) {
        System.setProperty("java.awt.headless", "true");
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.decode(accentHex));
            g.fillRect(0, 0, size, size);
            g.setColor(Color.WHITE);
            int ring = Math.round(size * 0.5f);
            int stroke = Math.max(2, Math.round(size * 0.07f));
            int offset = (size - ring) / 2;
            g.setStroke(new java.awt.BasicStroke(stroke));
            g.drawOval(offset, offset, ring, ring);
            int dot = Math.round(size * 0.18f);
            g.fillOval((size - dot) / 2, (size - dot) / 2, dot, dot);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot encode the PWA icon", exception);
        }
    }
}
