package com.npdev.dsl.v1.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FORM P3-T2: the loader for {@code expr/functions-stdlib.json}, the ONE list of expression-language
 * functions. {@link ValueBehaviorFunctions#NAMES} is derived from it, {@link StdlibFunctions} implements
 * the entries whose {@code impl} is {@code stdlib}, and the generator embeds {@link #json()} into the
 * browser engine, so the Java and JavaScript function tables cannot be maintained twice.
 */
public final class StdlibRegistry {

    /** One row of the JSON. {@code kind}: value | list | invariant | store. {@code impl}: stdlib or the owning engine. */
    public record Entry(String name, List<String> aliases, List<String> params, String returns, String kind,
                        String impl, boolean sql, boolean js, boolean isVolatile, boolean defaultOnly, String note) {

        public boolean implementedByStdlib() {
            return "stdlib".equals(impl);
        }

        /** The name plus every alias. */
        public List<String> allNames() {
            List<String> names = new ArrayList<>(aliases.size() + 1);
            names.add(name);
            names.addAll(aliases);
            return names;
        }
    }

    private static final String RESOURCE = "/expr/functions-stdlib.json";

    private static final String JSON = readResource();

    private static final List<Entry> ENTRIES = parse(JSON);

    private static final Map<String, Entry> BY_NAME = index(ENTRIES);

    private StdlibRegistry() {
    }

    /** The raw JSON text. */
    public static String json() {
        return JSON;
    }

    /** The {@code functions} array as compact JSON, which the generator embeds into the browser engine. */
    public static String functionsJson() {
        try {
            return new ObjectMapper().readTree(JSON).get("functions").toString();
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse " + RESOURCE, e);
        }
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** The entry for a canonical name or an alias (case-sensitive, as written in an expression). */
    public static Optional<Entry> find(String nameOrAlias) {
        return Optional.ofNullable(BY_NAME.get(nameOrAlias));
    }

    /** Every name and alias of the entries of the given kind that {@link StdlibFunctions} implements. */
    public static Set<String> implementedNames(String kind) {
        Set<String> out = new LinkedHashSet<>();
        for (Entry entry : ENTRIES) {
            if (entry.implementedByStdlib() && kind.equals(entry.kind())) {
                out.addAll(entry.allNames());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static String readResource() {
        try (InputStream in = StdlibRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    private static List<Entry> parse(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            List<Entry> out = new ArrayList<>();
            for (JsonNode f : root.get("functions")) {
                out.add(new Entry(
                        f.get("name").asText(),
                        strings(f.get("aliases")),
                        strings(f.get("params")),
                        f.get("returns").asText(),
                        f.get("kind").asText(),
                        f.get("impl").asText(),
                        f.path("sql").asBoolean(false),
                        f.path("js").asBoolean(false),
                        f.path("volatile").asBoolean(false),
                        f.path("defaultOnly").asBoolean(false),
                        f.path("note").asText("")));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse " + RESOURCE, e);
        }
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }

    private static Map<String, Entry> index(List<Entry> entries) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry entry : entries) {
            for (String name : entry.allNames()) {
                if (out.put(name, entry) != null) {
                    throw new IllegalStateException("duplicate function name in functions-stdlib.json: " + name);
                }
            }
        }
        return Map.copyOf(out);
    }
}
