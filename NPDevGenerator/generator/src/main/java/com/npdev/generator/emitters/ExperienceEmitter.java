package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProperty;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FORM P5-T2: writes {@code src/main/resources/npdev/experiences.json}, the RESOLVED form of the model's
 * {@code experiences[]} / {@code screens[]}, next to {@code compiled-model.json}. The runtime
 * ({@code ExperienceController}) reads it once at startup; the client renderer under {@code static/x/} renders
 * from what {@code GET /api/x/me} serves out of it. No per-app JavaScript is generated.
 *
 * <p>Resolution adds to each screen a {@code dataUrl} (the REST path its rows are fetched from: the concept's
 * generic collection route, or the named-query route) and a {@code fieldMeta} ({@code {field:{type,label}}} for the
 * plain field names the screen mentions: {@code type}, {@code label}, {@code required}, and {@code values} for an enum),
 * an {@code idField} (the key a row is fetched by, {@code dataUrl + "/" + id}), and to each action a {@code startUrl}
 * ({@code /api/x/flows/<flow>/execute}, the only route that starts an {@code expose: ui} flow from a browser).
 * Everything else is the screen exactly as authored. Experience order is declaration order, which is
 * load-bearing: a user lands in the first experience whose {@code for} matches one of their roles.
 *
 * <p>Written only when the model declares an experience or a screen; a model without either keeps its exact
 * output (a stale file from an earlier generation is removed). Also copies every file listed in the client
 * manifest {@code npdev-templates/experience/files.json} (a JSON array of relative paths) verbatim into
 * {@code static/x/<same path>}; a missing manifest copies nothing and is not an error.
 *
 * <p>FORM P5-T6 (maps): when the model uses a map anywhere, each experience also carries the RESOLVED
 * {@code mapTilesUrl} / {@code mapAttribution}: the default of the model property {@code mapTilesUrl} (and
 * {@code mapAttribution}) when the app declares one, else OpenStreetMap with its attribution. A screen whose
 * {@code map.tiles} names another property gets {@code map.tilesUrl} / {@code map.attribution} the same way. Resolved
 * HERE, at generation time, because the property store is optional at runtime and a browser must not need a second
 * request (or a key) to draw a map; the client never holds a tile URL of its own.
 *
 * <p>FORM P5-T7 (PWA): every experience carries {@code themeColor} / {@code backgroundColor}, and one whose
 * {@code device} is mobile or both (the default) gets its home-screen icons {@code static/x/icons/<name>-192.png} and
 * {@code -512.png} (see {@link ExperienceIcons}); {@code ExperienceController} serves them and the web manifest.
 */
public final class ExperienceEmitter extends AbstractEmitter {

    static final String OUTPUT = "src/main/resources/npdev/experiences.json";
    static final String STATIC_PREFIX = "src/main/resources/static/x/";
    static final String DEFAULT_CLIENT_BASE = "npdev-templates/experience/";
    static final String CLIENT_MANIFEST = "files.json";

    static final String ICONS_DIR = "icons/";
    static final int[] ICON_SIZES = {192, 512};
    static final String DEFAULT_THEME_COLOR = "#0b5fff";   // shell.css --np-accent
    static final String DEFAULT_BACKGROUND_COLOR = "#ffffff"; // shell.css --np-surface
    static final String DARK_THEME_COLOR = "#6fa8ff";
    static final String DARK_BACKGROUND_COLOR = "#14161a";
    static final String OSM_TILES_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    static final String OSM_ATTRIBUTION = "\u00a9 OpenStreetMap contributors";
    static final String TILES_PROPERTY = "mapTilesUrl";
    static final String ATTRIBUTION_PROPERTY = "mapAttribution";

    private static final Pattern TILES_URL = Pattern.compile("^(https?://\\S+|/[^/\\s]\\S*)$");
    private static final Pattern URL_HOST = Pattern.compile("^https?://([^/:?#{]+)");
    private static final Pattern PLAIN_FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String clientBase;

    public ExperienceEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        this(templates, writer, DEFAULT_CLIENT_BASE);
    }

    /** {@code clientBase}: the classpath directory holding the client manifest and the files it names. */
    ExperienceEmitter(TemplateEngine templates, GeneratedSourceWriter writer, String clientBase) {
        super(templates, writer);
        this.clientBase = clientBase.endsWith("/") ? clientBase : clientBase + "/";
    }

    public void emit(CompiledModel model) {
        if (model == null || (model.getExperiences().isEmpty() && clientScreens(model).isEmpty())) {
            writer.deleteRelativeIfExists(OUTPUT);
            return;
        }
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode experiences = root.putArray("experiences");
        Tiles globalTiles = usesMaps(model) ? tiles(model, TILES_PROPERTY) : null;
        for (JsonNode experience : model.getExperiences()) {
            ObjectNode resolved = (ObjectNode) experience.deepCopy();
            String[] colors = colors(experience);
            resolved.put("themeColor", colors[0]);
            resolved.put("backgroundColor", colors[1]);
            if (globalTiles != null) {
                resolved.put("mapTilesUrl", globalTiles.url());
                resolved.put("mapAttribution", globalTiles.attribution());
            }
            experiences.add(resolved);
            writeIcons(experience, colors[0]);
        }
        ArrayNode screens = root.putArray("screens");
        for (JsonNode screen : clientScreens(model)) {
            screens.add(resolveScreen(model, screen));
        }
        try {
            writer.writeRelative(OUTPUT, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Failed serialising " + OUTPUT, e);
        }
        copyClientFiles(model);
    }

    // ------------------------------------------------------------------ resolution

    private static ObjectNode resolveScreen(CompiledModel model, JsonNode source) {
        ObjectNode screen = (ObjectNode) source.deepCopy();
        String conceptName = text(screen, "concept");
        String queryName = text(screen, "query");

        CompiledConcept concept = findConcept(model, conceptName);
        CompiledQuery query = null;
        if (hasText(queryName)) {
            for (CompiledQuery candidate : model.getQueries()) {
                if (queryName.equalsIgnoreCase(candidate.name())) {
                    query = candidate;
                    break;
                }
            }
        }
        if (concept != null) {
            screen.put("dataUrl", SqlIdentifierSupport.conceptEndpointBase(concept, model.getContexts()));
        } else if (hasText(queryName)) {
            screen.put("dataUrl", "/api/queries/" + (query == null ? queryName : query.name()));
        }

        // A plain query shows its concept's own fields; a grouped/computed one shows other columns, so no meta.
        CompiledConcept fieldSource = concept;
        if (fieldSource == null && query != null && hasText(query.concept()) && query.groupBy().isEmpty()
                && query.aggregates().isEmpty() && query.computed().isEmpty() && query.lookups().isEmpty()) {
            fieldSource = findConcept(model, query.concept());
        }
        // The key a row is fetched by (dataUrl + "/" + row[idField]); "id" unless the concept names another id field.
        screen.put("idField", idFieldName(fieldSource));
        ObjectNode fieldMeta = screen.putObject("fieldMeta");
        if (fieldSource != null) {
            addMeta(fieldMeta, fieldSource, screen.get("columns"));
            addMeta(fieldMeta, fieldSource, screen.get("fields"));
            addMeta(fieldMeta, fieldSource, screen.get("groupBy"));
            JsonNode map = screen.get("map");
            if (map != null && map.isObject()) {
                addMeta(fieldMeta, fieldSource, map.get("lat"));
                addMeta(fieldMeta, fieldSource, map.get("lng"));
                JsonNode pickPoints = map.get("pickPoints");
                if (pickPoints != null && pickPoints.isArray()) {
                    for (JsonNode point : pickPoints) {
                        addMeta(fieldMeta, fieldSource, point.get("lat"));
                        addMeta(fieldMeta, fieldSource, point.get("lng"));
                    }
                }
            }
        }

        JsonNode ownMap = screen.get("map");
        if (ownMap instanceof ObjectNode mapNode && hasText(text(mapNode, "tiles"))) {
            Tiles own = tiles(model, text(mapNode, "tiles"));
            mapNode.put("tilesUrl", own.url());
            mapNode.put("attribution", own.attribution());
        }

        JsonNode actions = screen.get("actions");
        if (actions != null && actions.isArray()) {
            for (JsonNode action : actions) {
                String flow = text(action, "flow");
                if (hasText(flow) && action instanceof ObjectNode actionNode) {
                    actionNode.put("startUrl", "/api/x/flows/" + canonicalFlowName(model, flow) + "/execute");
                }
            }
        }
        return screen;
    }

    /** Adds meta for a field name, or for every entry of a {@code columns}/{@code fields} array. */
    private static void addMeta(ObjectNode into, CompiledConcept concept, JsonNode reference) {
        if (reference == null) {
            return;
        }
        if (reference.isArray()) {
            for (JsonNode entry : reference) {
                addMeta(into, concept, entry);
            }
            return;
        }
        String name = reference.isTextual() ? reference.asText() : text(reference, "value");
        if (!hasText(name) || !PLAIN_FIELD.matcher(name).matches() || into.has(name)) {
            return;
        }
        for (CompiledField field : concept.getFields()) {
            if (name.equalsIgnoreCase(field.getName())) {
                ObjectNode meta = into.putObject(name);
                meta.put("type", field.getDslType() == null ? "" : field.getDslType());
                String label = field.getUi() == null ? null : field.getUi().getLabel();
                // No declared label: send none, so the browser humanizes the name ("riderAccount" -> "Rider account")
                // instead of showing the raw field name.
                if (hasText(label)) {
                    meta.put("label", label);
                }
                meta.put("required", field.isRequired());
                if (!field.getEnumValues().isEmpty()) {
                    ArrayNode values = meta.putArray("values");
                    field.getEnumValues().forEach(values::add);
                }
                return;
            }
        }
    }

    private static String idFieldName(CompiledConcept concept) {
        if (concept != null) {
            for (CompiledField field : concept.getFields()) {
                if (field.isId() && hasText(field.getName())) {
                    return field.getName();
                }
            }
        }
        return "id";
    }

    private static CompiledConcept findConcept(CompiledModel model, String name) {
        if (!hasText(name)) {
            return null;
        }
        for (CompiledConcept concept : model.getConcepts()) {
            if (name.equalsIgnoreCase(concept.getName())) {
                return concept;
            }
        }
        return null;
    }

    private static String canonicalFlowName(CompiledModel model, String flow) {
        for (CompiledFlow candidate : model.getFlows()) {
            if (flow.equalsIgnoreCase(candidate.getName())) {
                return candidate.getName();
            }
        }
        return flow;
    }

    // ------------------------------------------------------------------ maps (P5-T6)

    private record Tiles(String url, String attribution) {
    }

    /**
     * The screens the {@code /x/} client serves. A screen carrying an {@code admin} block (D-1) stands for a concept's
     * generated back-office pages, which the Business UI renders; the experience client never shows it.
     */
    private static List<JsonNode> clientScreens(CompiledModel model) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode screen : model.getScreens()) {
            if (!screen.has("admin")) {
                out.add(screen);
            }
        }
        return out;
    }

    /** True when any screen shows a map (a {@code map} spec, kind {@code map}, or a {@code map} dashboard widget). */
    private static boolean usesMaps(CompiledModel model) {
        for (JsonNode screen : clientScreens(model)) {
            if (screen.has("map") || "map".equals(text(screen, "kind"))) {
                return true;
            }
            JsonNode widgets = screen.get("widgets");
            if (widgets != null && widgets.isArray()) {
                for (JsonNode widget : widgets) {
                    if ("map".equals(text(widget, "kind"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The tile template and attribution for the model property {@code property}: its declared default when that is a
     * usable http(s) or same-origin URL, else the global {@code mapTilesUrl}, else OpenStreetMap. Attribution is never
     * empty: the property {@code <property>Attribution} ({@code mapAttribution} for the global one), else OSM's own
     * text for the OSM default, else the tile host.
     */
    private static Tiles tiles(CompiledModel model, String property) {
        String url = tilesUrlDefault(model, property);
        if (url == null && !TILES_PROPERTY.equalsIgnoreCase(property)) {
            url = tilesUrlDefault(model, TILES_PROPERTY);
        }
        if (url == null) {
            url = OSM_TILES_URL;
        }
        String attributionProperty = TILES_PROPERTY.equalsIgnoreCase(property) ? ATTRIBUTION_PROPERTY : property + "Attribution";
        String attribution = propertyText(model, attributionProperty);
        if (!hasText(attribution)) {
            Matcher host = URL_HOST.matcher(url);
            attribution = OSM_TILES_URL.equals(url) ? OSM_ATTRIBUTION : "Map tiles: " + (host.find() ? host.group(1) : "provider");
        }
        return new Tiles(url, attribution);
    }

    private static String tilesUrlDefault(CompiledModel model, String property) {
        String url = propertyText(model, property);
        return hasText(url) && TILES_URL.matcher(url.trim()).matches() ? url.trim() : null;
    }

    private static String propertyText(CompiledModel model, String name) {
        for (CompiledProperty property : model.getProperties()) {
            if (name.equalsIgnoreCase(property.name()) && property.defaultValue() instanceof String value) {
                return value;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ installable experiences (P5-T7)

    /**
     * {themeColor, backgroundColor} as lowercase hex. The schema types {@code experiences[].theme} as a theme id
     * (a string), so the only choice here is the dark palette for {@code "dark"} and the shell defaults otherwise.
     */
    private static String[] colors(JsonNode experience) {
        JsonNode theme = experience.get("theme");
        boolean dark = theme != null && theme.isTextual() && "dark".equalsIgnoreCase(theme.asText());
        return new String[] {
                dark ? DARK_THEME_COLOR : DEFAULT_THEME_COLOR,
                dark ? DARK_BACKGROUND_COLOR : DEFAULT_BACKGROUND_COLOR};
    }

    /** The file-name stem of an experience's icons; ExperienceController derives the same stem from the URL name. */
    static String iconStem(String experienceName) {
        return experienceName.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private void writeIcons(JsonNode experience, String themeColor) {
        String name = text(experience, "name");
        if (!hasText(name) || "web".equals(text(experience, "device"))) {
            return;
        }
        String title = text(experience, "title");
        char letter = ExperienceIcons.initial(hasText(title) ? title : name);
        int rgb = Integer.parseInt(themeColor.substring(1), 16);
        for (int size : ICON_SIZES) {
            writer.writeRelativeBytes(STATIC_PREFIX + ICONS_DIR + iconStem(name) + "-" + size + ".png",
                    ExperienceIcons.png(letter, size, rgb));
        }
    }

    // ------------------------------------------------------------------ client files

    /**
     * REG-349: {@code static/x/strings.js}, a classic script index.html loads right after {@code /npdev-i18n.js}: it hands
     * the client its text catalogue ({@code experience.*} ids only, every shipped language, the model's own overrides
     * applied, plus the app's default language) to {@code NPDevI18n.use}. A static file, so the client needs no extra
     * request and no sign-in to read it. ASCII only (non-ASCII characters are written as JSON unicode escapes).
     */
    private void writeClientStrings(CompiledModel model) {
        ObjectNode catalogue = MAPPER.createObjectNode();
        catalogue.set("strings", MAPPER.valueToTree(onlyExperienceIds(UiStrings.appStrings(model))));
        ObjectNode byLocale = catalogue.putObject("stringsByLocale");
        UiStrings.byLocale(model).forEach((language, strings) -> byLocale.set(language, MAPPER.valueToTree(onlyExperienceIds(strings))));
        catalogue.put("defaultLanguage", UiStrings.appLanguage(model));
        String json = UiStrings.json(catalogue);
        StringBuilder ascii = new StringBuilder(json.length() + 64);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x80) {
                ascii.append(c);
            } else {
                ascii.append(String.format("\\u%04x", (int) c));
            }
        }
        writer.writeRelative(STATIC_PREFIX + "strings.js", "NPDevI18n.use(" + ascii + ");\n");
    }

    private static Map<String, String> onlyExperienceIds(Map<String, String> all) {
        Map<String, String> out = new LinkedHashMap<>();
        all.forEach((id, text) -> {
            if (id.startsWith("experience.")) {
                out.put(id, text);
            }
        });
        return out;
    }

    private void copyClientFiles(CompiledModel model) {
        String manifest = readClasspath(clientBase + CLIENT_MANIFEST);
        if (manifest == null) {
            return;
        }
        JsonNode listed;
        try {
            listed = MAPPER.readTree(manifest);
        } catch (IOException e) {
            throw new IllegalStateException("Experience client manifest " + clientBase + CLIENT_MANIFEST
                    + " is not valid JSON", e);
        }
        if (listed == null || !listed.isArray()) {
            throw new IllegalStateException("Experience client manifest " + clientBase + CLIENT_MANIFEST
                    + " must be a JSON array of relative file paths");
        }
        for (JsonNode entry : listed) {
            String relative = entry.asText("").trim().replace('\\', '/');
            if (relative.isEmpty() || relative.startsWith("/") || relative.contains("..") || relative.contains(":")) {
                throw new IllegalStateException("Experience client manifest entry '" + relative
                        + "' must be a relative path inside the client directory");
            }
            byte[] bytes = readClasspathBytes(clientBase + relative);
            if (bytes == null) {
                throw new IllegalStateException("Experience client manifest lists '" + relative
                        + "' but " + clientBase + relative + " is not on the classpath");
            }
            writer.writeRelativeBytes(STATIC_PREFIX + relative, bytes);
        }
        writeClientStrings(model);
    }

    private static String readClasspath(String path) {
        byte[] bytes = readClasspathBytes(path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readClasspathBytes(String path) {
        try (InputStream in = ExperienceEmitter.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading " + path, e);
        }
    }

    private static String text(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
