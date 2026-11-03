package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;
import com.npdev.generator.themes.ThemeCatalog;
import com.npdev.generator.themes.ThemeCatalog.ThemeException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * THM-6: the theme files a generated app ships, and the generation-time proof that every theme it could show is
 * valid and readable.
 *
 * <p>Emitted under {@code static/}: {@code npdev-theme.js} (the theme and accessibility engine, byte-identical to
 * NPDevContract/themes/theme-engine.js -- it is copied from the classpath, not re-typed), {@code npdev-a11y.css},
 * {@code npdev-display.js} (what the shell and the sign-in pages use to choose and remember a theme), and
 * {@code themes/<id>.json} for the app's theme, every theme its end users may choose and every {@code basedOn}
 * ancestor, plus {@code themes/index.json} (sorted, so generation stays deterministic).
 *
 * <p>An invalid theme FAILS GENERATION naming the file and the colour pair, e.g. {@code themes/summer.json:
 * colors: textMuted on bg is 3.10:1, needs 4.5:1}. Nothing is fetched from anywhere at runtime: a theme is copied
 * in here, at generation time, never loaded from a catalogue by the running app.
 */
public final class ThemeEmitter extends AbstractEmitter {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String STATIC = "src/main/resources/static/";

    public ThemeEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    /**
     * @param modelSourcePath the model's own file; the app's themes are {@code themes/*.json} beside it. May be null
     *     (a model compiled from memory), in which case only the built-in themes exist.
     */
    public void emit(SettingResolver settings, Path modelSourcePath) {
        String name = settings.value(NpdevSettings.UI_THEME_NAME, SettingTarget.app());
        String choicesRaw = settings.value(NpdevSettings.UI_THEME_CHOICES, SettingTarget.app());
        String mode = settings.value(NpdevSettings.UI_THEME_MODE, SettingTarget.app());
        Path themesDir = modelSourcePath == null || modelSourcePath.toAbsolutePath().getParent() == null
                ? null : modelSourcePath.toAbsolutePath().getParent().resolve("themes");

        Map<String, JsonNode> builtIns = ThemeCatalog.builtIns();
        Map<String, JsonNode> own = ThemeCatalog.appThemes(themesDir);
        Map<String, JsonNode> catalog = new LinkedHashMap<>(builtIns);
        catalog.putAll(own);

        List<String> problems = new ArrayList<>();
        // every theme the app owns is checked, chosen or not: a broken themes/x.json is a defect whatever picks it
        for (Map.Entry<String, JsonNode> e : own.entrySet()) {
            for (String p : ThemeCatalog.validate(e.getValue(), catalog)) {
                problems.add("themes/" + e.getKey() + ".json: " + p);
            }
        }
        if (!catalog.containsKey(name)) {
            problems.add("ui.theme.name is \"" + name + "\", which is not a theme (built-in: " + ThemeCatalog.BUILT_IN_IDS
                    + (own.isEmpty() ? "" : "; yours: " + own.keySet()) + ")");
        }
        List<String> choices = new ArrayList<>();
        if (choicesRaw != null && choicesRaw.trim().equals("*")) {
            choices.addAll(catalog.keySet());
        } else if (choicesRaw != null && !choicesRaw.isBlank()) {
            for (String part : choicesRaw.split(",")) {
                String id = part.trim();
                if (id.isEmpty() || choices.contains(id)) {
                    continue;
                }
                if (!catalog.containsKey(id)) {
                    problems.add("ui.theme.choices names \"" + id + "\", which is not a theme");
                } else {
                    choices.add(id);
                }
            }
        }
        // the built-ins were validated once when they were added to the platform (check-themes.py); re-checking
        // the ones an app uses costs nothing and catches a corrupted jar
        Set<String> wanted = new LinkedHashSet<>();
        if (catalog.containsKey(name)) {
            wanted.add(name);
        }
        wanted.addAll(choices);
        for (String id : wanted) {
            if (builtIns.containsKey(id)) {
                for (String p : ThemeCatalog.validate(catalog.get(id), catalog)) {
                    problems.add("built-in theme " + id + ": " + p);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ThemeException("The app's themes are not valid:\n  - " + String.join("\n  - ", problems));
        }

        // what ships: the app's theme, its choices and every ancestor of those -- sorted for determinism
        Set<String> ship = new TreeSet<>();
        for (String id : wanted) {
            ship.addAll(ThemeCatalog.withAncestors(id, catalog));
        }
        for (String id : ship) {
            writer.writeRelative(STATIC + "themes/" + id + ".json", pretty(catalog.get(id)));
        }
        ObjectNode index = MAPPER.createObjectNode();
        index.put("schema", "npdev-theme-index.v1");
        index.put("default", name);
        index.put("mode", mode);
        ArrayNode choiceNodes = index.putArray("choices");
        choices.forEach(choiceNodes::add);
        ArrayNode themes = index.putArray("themes");
        for (String id : ship) {
            JsonNode t = catalog.get(id);
            ObjectNode row = themes.addObject();
            row.put("id", id);
            row.put("name", t.path("name").asText(id));
            if (t.has("names")) {
                row.set("names", t.get("names"));
            }
            row.put("colorScheme", t.path("colorScheme").asText("light"));
            row.put("hasDark", ThemeCatalog.resolve(t, catalog).has("darkColors"));
            if (t.hasNonNull("basedOn")) {
                row.put("basedOn", t.get("basedOn").asText());
            }
            row.set("swatches", ThemeCatalog.swatches(t, catalog));
        }
        writer.writeRelative(STATIC + "themes/index.json", pretty(index));

        writer.writeRelative(STATIC + "npdev-theme.js", classpath(ThemeCatalog.ENGINE_RESOURCE));
        writer.writeRelative(STATIC + "npdev-a11y.css", classpath(ThemeCatalog.A11Y_CSS_RESOURCE));
        writer.writeRelative(STATIC + "npdev-display.js", templates.raw("static-assets/display/npdev-display.js"));
    }

    /** What the business UI manifest's {@code theme} block carries (see BusinessUiEmitter). */
    public static List<String> parseChoices(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw.trim().equals("*")) {
            out.add("*");
            return out;
        }
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static String pretty(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String classpath(String resource) {
        try (InputStream in = ThemeEmitter.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing from the generator jar: " + resource
                        + " (the build copies NPDevContract/themes into it)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
