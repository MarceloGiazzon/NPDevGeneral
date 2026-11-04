package com.npdev.generator.themes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * THM-6: the themes a generated app can use, and the check that every one of them is safe and readable.
 *
 * <p>A theme is DATA ({@code npdev-theme.v1}, NPDevContract/themes/theme.schema.json): thirteen hex colours, two
 * font lists from a safe character set, a radius and an elevation. It can never carry CSS, so a theme from any
 * source (an AI, a catalogue, a stranger) cannot inject styles or fetch a remote resource. This class is the Java
 * twin of {@code validate()} in the theme engine ({@code theme-engine.js}, which the app's browser runs) and of
 * {@code scripts/quality/check-themes.py}: the same structure rules and the same WCAG 2.2 contrast floors, so a
 * theme that passes here cannot be refused in the user's browser. An invalid theme FAILS GENERATION with a message
 * that names the file and the colour pair.
 *
 * <p>Built-in themes come from the generator's classpath ({@code npdev-themes/<id>.json}, copied from
 * NPDevContract/themes by the build); an app's own themes are {@code themes/*.json} next to its model.json.
 */
public final class ThemeCatalog {
    /** The platform's built-in themes; the ids are reserved, so an app cannot redefine one. */
    public static final List<String> BUILT_IN_IDS = List.of(
            "npdev-default", "npdev-dark", "npdev-light", "retro", "synth", "farm", "warm", "windows", "high-contrast");
    public static final String DEFAULT_THEME_ID = "npdev-default";
    public static final String ENGINE_RESOURCE = "npdev-themes/theme-engine.js";
    public static final String A11Y_CSS_RESOURCE = "npdev-themes/a11y.css";

    static final List<String> TOKENS = List.of("bg", "surface", "surfaceRaised", "border", "borderStrong", "text",
            "textMuted", "accent", "onAccent", "positive", "negative", "warning", "info");
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern FONT = Pattern.compile("^[A-Za-z0-9 ,\"'-]{1,200}$");
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");
    private static final Set<String> ELEVATIONS = Set.of("flat", "soft", "bevel", "glow");
    private static final Set<String> ALLOWED_KEYS = Set.of("$schema", "schema", "id", "name", "names", "version",
            "author", "description", "descriptions", "license", "colorScheme", "basedOn", "colors", "darkColors",
            "typography", "shape");
    private static final int MAX_THEME_BYTES = 20_000;

    /** foreground, background, minimum ratio -- WCAG 2.2: 1.4.3 text 4.5:1, 1.4.11 control edges 3:1. */
    private static final String[][] CONTRAST_RULES = {
        {"text", "bg", "4.5"}, {"text", "surface", "4.5"}, {"text", "surfaceRaised", "4.5"},
        {"textMuted", "bg", "4.5"}, {"textMuted", "surface", "4.5"},
        {"accent", "bg", "4.5"}, {"accent", "surface", "4.5"}, {"onAccent", "accent", "4.5"},
        {"positive", "surface", "4.5"}, {"negative", "surface", "4.5"},
        {"warning", "surface", "4.5"}, {"info", "surface", "4.5"},
        {"borderStrong", "surface", "3.0"}, {"borderStrong", "bg", "3.0"}
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ThemeCatalog() {}

    // ------------------------------------------------------------------------------------ loading

    public static Map<String, JsonNode> builtIns() {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String id : BUILT_IN_IDS) {
            String resource = "npdev-themes/" + id + ".json";
            try (InputStream in = ThemeCatalog.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("built-in theme missing from the generator jar: " + resource);
                }
                out.put(id, MAPPER.readTree(in));
            } catch (IOException e) {
                throw new IllegalStateException("built-in theme unreadable: " + resource + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    /** The app's own themes: {@code <dir>/*.json}, sorted by file name. A missing folder is no themes, not an error. */
    public static Map<String, JsonNode> appThemes(Path dir) {
        Map<String, JsonNode> out = new TreeMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
        } catch (IOException e) {
            throw new ThemeException("cannot read the themes folder " + dir + ": " + e.getMessage());
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                if (Files.size(file) > MAX_THEME_BYTES) {
                    throw new ThemeException(name + ": the theme file is larger than " + MAX_THEME_BYTES + " bytes");
                }
                JsonNode theme = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
                String id = theme.path("id").asText("");
                if (!name.equals(id + ".json")) {
                    throw new ThemeException(name + ": the file name must be <id>.json (the theme's id is \"" + id + "\")");
                }
                if (BUILT_IN_IDS.contains(id)) {
                    throw new ThemeException(name + ": \"" + id + "\" is one of NPDev's own themes; give yours a different id");
                }
                out.put(id, theme);
            } catch (IOException e) {
                throw new ThemeException(name + ": not valid JSON: " + e.getMessage());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------- resolution

    /** Flattens {@code basedOn} (depth at most 3, cycles refused), like {@code resolve()} in the engine. */
    public static ObjectNode resolve(JsonNode theme, Map<String, JsonNode> catalog) {
        List<JsonNode> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JsonNode cur = theme;
        while (cur != null) {
            String id = cur.path("id").asText("");
            if (!seen.add(id)) {
                throw new ThemeException("basedOn cycle at " + id);
            }
            chain.add(0, cur);
            if (!cur.hasNonNull("basedOn")) {
                break;
            }
            if (chain.size() > 3) {
                throw new ThemeException("basedOn chain deeper than 3 (at " + id + ")");
            }
            String parent = cur.get("basedOn").asText();
            cur = catalog.get(parent);
            if (cur == null) {
                throw new ThemeException("basedOn: unknown theme \"" + parent + "\"");
            }
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.set("colors", MAPPER.createObjectNode());
        out.set("typography", MAPPER.createObjectNode());
        out.set("shape", MAPPER.createObjectNode());
        for (JsonNode t : chain) {
            Iterator<Map.Entry<String, JsonNode>> it = t.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey();
                if (k.equals("colors") || k.equals("typography") || k.equals("shape")) {
                    ((ObjectNode) out.get(k)).setAll((ObjectNode) e.getValue());
                } else if (k.equals("darkColors")) {
                    if (!out.has("darkColors")) {
                        out.set("darkColors", MAPPER.createObjectNode());
                    }
                    ((ObjectNode) out.get("darkColors")).setAll((ObjectNode) e.getValue());
                } else if (!k.equals("basedOn")) {
                    out.set(k, e.getValue());
                }
            }
        }
        return out;
    }

    /** {@code theme} plus every ancestor in its {@code basedOn} chain, ancestors first -- what the app must ship. */
    public static List<String> withAncestors(String id, Map<String, JsonNode> catalog) {
        List<String> chain = new ArrayList<>();
        String cur = id;
        while (cur != null && !chain.contains(cur)) {
            JsonNode t = catalog.get(cur);
            if (t == null) {
                throw new ThemeException("unknown theme \"" + cur + "\"");
            }
            chain.add(0, cur);
            cur = t.hasNonNull("basedOn") ? t.get("basedOn").asText() : null;
        }
        return chain;
    }

    // ---------------------------------------------------------------------------------- validation

    /** Structure and contrast of a theme (resolved first when it uses {@code basedOn}). Returns plain-language problems. */
    public static List<String> validate(JsonNode theme, Map<String, JsonNode> catalog) {
        List<String> errors = new ArrayList<>(validateStructure(theme));
        if (!errors.isEmpty()) {
            return errors;
        }
        JsonNode flat = theme;
        try {
            if (theme.hasNonNull("basedOn")) {
                flat = resolve(theme, catalog);
                errors.addAll(validateStructure(withIdentity(flat, theme)));
            }
        } catch (ThemeException e) {
            errors.add(e.getMessage());
            return errors;
        }
        boolean strict = "high-contrast".equals(theme.path("id").asText());
        for (String set : List.of("colors", "darkColors")) {
            JsonNode colors = flat.get(set);
            if (colors == null || !completeSet(colors)) {
                continue;
            }
            for (String[] rule : CONTRAST_RULES) {
                double floor = Double.parseDouble(rule[2]);
                double min = strict && floor == 4.5 ? 7.0 : floor;
                double ratio = contrastRatio(colors.get(rule[0]).asText(), colors.get(rule[1]).asText());
                if (ratio < min) {
                    errors.add(set + ": " + rule[0] + " on " + rule[1] + " is "
                            + String.format(java.util.Locale.ROOT, "%.2f", Math.floor(ratio * 100) / 100)
                            + ":1, needs " + (min == Math.rint(min) ? String.valueOf((int) min) : String.valueOf(min)) + ":1");
                }
            }
        }
        return errors;
    }

    private static JsonNode withIdentity(JsonNode flat, JsonNode original) {
        ObjectNode copy = ((ObjectNode) flat).deepCopy();
        for (String k : List.of("schema", "id", "name", "version", "colorScheme")) {
            if (!copy.has(k) && original.has(k)) {
                copy.set(k, original.get(k));
            }
        }
        return copy;
    }

    private static boolean completeSet(JsonNode colors) {
        for (String t : TOKENS) {
            if (!colors.hasNonNull(t) || !HEX.matcher(colors.get(t).asText()).matches()) {
                return false;
            }
        }
        return true;
    }

    static List<String> validateStructure(JsonNode theme) {
        List<String> errors = new ArrayList<>();
        if (theme == null || !theme.isObject()) {
            return List.of("not a JSON object");
        }
        if (!"npdev-theme.v1".equals(theme.path("schema").asText())) {
            errors.add("schema must be \"npdev-theme.v1\"");
        }
        if (!ID.matcher(theme.path("id").asText("")).matches()) {
            errors.add("id must match " + ID.pattern());
        }
        String name = theme.path("name").asText("");
        if (name.isEmpty() || name.length() > 40) {
            errors.add("name is required (max 40 characters)");
        }
        String scheme = theme.path("colorScheme").asText("");
        if (!scheme.equals("light") && !scheme.equals("dark")) {
            errors.add("colorScheme must be light or dark");
        }
        theme.fieldNames().forEachRemaining(k -> {
            if (!ALLOWED_KEYS.contains(k)) {
                errors.add("unknown key: " + k);
            }
        });
        for (String set : List.of("colors", "darkColors")) {
            JsonNode colors = theme.get(set);
            if (colors == null) {
                continue;
            }
            if (!colors.isObject()) {
                errors.add(set + " must be an object");
                continue;
            }
            colors.fields().forEachRemaining(e -> {
                if (!TOKENS.contains(e.getKey())) {
                    errors.add(set + "." + e.getKey() + " is not a token");
                } else if (!e.getValue().isTextual() || !HEX.matcher(e.getValue().asText()).matches()) {
                    errors.add(set + "." + e.getKey() + " must be #rrggbb, got " + e.getValue());
                }
            });
        }
        if (!theme.hasNonNull("basedOn")) {
            for (String t : TOKENS) {
                if (!theme.path("colors").hasNonNull(t)) {
                    errors.add("colors." + t + " is required (or use basedOn)");
                }
            }
        }
        if (theme.has("darkColors") && !"light".equals(scheme)) {
            errors.add("a theme with darkColors must have colorScheme light");
        }
        JsonNode typography = theme.path("typography");
        for (String k : List.of("fontUi", "fontMono")) {
            if (typography.has(k) && !FONT.matcher(typography.get(k).asText()).matches()) {
                errors.add("typography." + k + " has characters outside [A-Za-z0-9 ,\"'-]");
            }
        }
        JsonNode shape = theme.path("shape");
        if (shape.has("radius") && !(shape.get("radius").isInt() && shape.get("radius").asInt() >= 0 && shape.get("radius").asInt() <= 24)) {
            errors.add("shape.radius must be an integer 0..24");
        }
        if (shape.has("elevation") && !ELEVATIONS.contains(shape.get("elevation").asText())) {
            errors.add("shape.elevation must be flat, soft, bevel or glow");
        }
        return errors;
    }

    // -------------------------------------------------------------------------------------- colour

    public static double contrastRatio(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        int n = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255);
    }

    private static double channel(int c8) {
        double c = c8 / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    // ------------------------------------------------------------------------- the app's own choice

    /** The five colours a gallery card shows, resolved. */
    public static ArrayNode swatches(JsonNode theme, Map<String, JsonNode> catalog) {
        JsonNode colors = resolve(theme, catalog).path("colors");
        ArrayNode out = MAPPER.createArrayNode();
        for (String t : List.of("bg", "surface", "accent", "positive", "negative")) {
            out.add(colors.path(t).asText(""));
        }
        return out;
    }

    public static final class ThemeException extends RuntimeException {
        public ThemeException(String message) {
            super(message);
        }
    }
}
