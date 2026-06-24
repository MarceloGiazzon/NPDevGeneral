package com.npdev.generator.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.packs.BuiltinPackComposer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits {@code npdev/store/pack-catalog.json}: the "app store" view — every built-in platform pack
 * found under {@code NPDevContract/packs} (name, version, description, concept count), flagged
 * {@code included} when {@code internal.tables} composed it into this app. Read-only and
 * informational: it does not install/compose anything itself (that already happens at generation
 * time via {@link BuiltinPackComposer}) — it just makes the catalog of what's available, and
 * what's already in this app, inspectable from the admin UI.
 *
 * <p>Best-effort: if the platform packs directory can't be located (e.g. a generator invocation far
 * from a real NPDev workspace checkout), the catalog is emitted as empty with
 * {@code discoverable:false} rather than failing generation.</p>
 */
public final class PackCatalogEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/npdev/store/pack-catalog.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void emit(GeneratedSourceWriter writer, boolean internalTablesEnabled) {
        emit(writer, internalTablesEnabled, List.of());
    }

    /** {@code installedPackAliases} mirrors config.json's packs.included list (see GeneratorMain) --
     *  a pack named there is "included" exactly like a built-in pack, even though it composes as an
     *  ordinary (non-admin-gated) business concept rather than an internal table. */
    public void emit(GeneratedSourceWriter writer, boolean internalTablesEnabled, List<String> installedPackAliases) {
        writer.writeRelative(RELATIVE_PATH, toJson(internalTablesEnabled, installedPackAliases));
    }

    private static String toJson(boolean internalTablesEnabled, List<String> installedPackAliases) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "npdev-pack-catalog.v1");
        Path packsDir = locatePlatformPacksDir(Path.of("").toAbsolutePath().normalize());
        root.put("discoverable", packsDir != null);
        root.put("packs", packsDir == null ? List.of() : readCatalog(packsDir, internalTablesEnabled, installedPackAliases));
        try {
            return OBJECT_MAPPER.writeValueAsString(root) + System.lineSeparator();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize pack catalog", exception);
        }
    }

    private static List<Map<String, Object>> readCatalog(Path packsDir, boolean internalTablesEnabled, List<String> installedPackAliases) {
        List<Map<String, Object>> packs = new ArrayList<>();
        try (var stream = Files.list(packsDir)) {
            List<Path> packDirs = new ArrayList<>();
            stream.filter(Files::isDirectory).forEach(packDirs::add);
            packDirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path packDir : packDirs) {
                Path packJson = packDir.resolve("pack.json");
                if (!Files.isRegularFile(packJson)) {
                    continue;
                }
                packs.add(readPackEntry(packDir.getFileName().toString(), packJson, internalTablesEnabled, installedPackAliases));
            }
        } catch (IOException exception) {
            return List.of();
        }
        return packs;
    }

    private static Map<String, Object> readPackEntry(
            String alias, Path packJson, boolean internalTablesEnabled, List<String> installedPackAliases) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("alias", alias);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(packJson.toFile());
            entry.put("name", root.path("pack").asText(alias));
            entry.put("version", root.path("version").asText("UNKNOWN"));
            entry.put("description", root.path("description").asText(""));
            entry.put("conceptCount", root.path("concepts").isArray() ? root.path("concepts").size() : 0);
            entry.put("conceptNames", conceptNames(root.path("concepts")));
            entry.put("concepts", conceptDetails(root.path("concepts")));
            entry.put("category", root.path("category").asText(""));
            entry.put("author", root.path("author").asText(""));
            Map<String, Object> forkedFrom = forkedFrom(root.path("forkedFrom"));
            entry.put("forkedFrom", forkedFrom);
            entry.put("forkedFromExists", forkedFrom == null
                    ? null : packExists(packJson.getParent().getParent(), String.valueOf(forkedFrom.get("pack"))));
        } catch (IOException exception) {
            entry.put("name", alias);
            entry.put("version", "UNKNOWN");
            entry.put("description", "");
            entry.put("conceptCount", 0);
            entry.put("conceptNames", List.of());
            entry.put("concepts", List.of());
            entry.put("category", "");
            entry.put("author", "");
            entry.put("forkedFrom", null);
            entry.put("forkedFromExists", null);
        }
        boolean isBuiltinIncluded = internalTablesEnabled && BuiltinPackComposer.BUILTIN_PACK_ALIASES.contains(alias);
        entry.put("included", isBuiltinIncluded || installedPackAliases.contains(alias));
        return entry;
    }

    private static List<String> conceptNames(JsonNode conceptsNode) {
        List<String> names = new ArrayList<>();
        if (conceptsNode != null && conceptsNode.isArray()) {
            for (JsonNode concept : conceptsNode) {
                String name = concept.path("name").asText("");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Field-level detail per concept (name/type/reference target), for the Store's box-authoring
     * drill-down and visual graph view: a read-only way to browse a candidate pack's concepts down
     * to their fields (and bond/reference relationships between them) before installing it -- not
     * an editor of the pack's own declared fields, which stay exactly as authored in pack.json.
     */
    private static List<Map<String, Object>> conceptDetails(JsonNode conceptsNode) {
        List<Map<String, Object>> concepts = new ArrayList<>();
        if (conceptsNode == null || !conceptsNode.isArray()) {
            return concepts;
        }
        for (JsonNode concept : conceptsNode) {
            String name = concept.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            Map<String, Object> conceptEntry = new LinkedHashMap<>();
            conceptEntry.put("name", name);
            List<Map<String, Object>> fields = new ArrayList<>();
            JsonNode fieldsNode = concept.path("fields");
            if (fieldsNode.isArray()) {
                for (JsonNode field : fieldsNode) {
                    String fieldName = field.path("name").asText("");
                    if (fieldName.isBlank()) {
                        continue;
                    }
                    Map<String, Object> fieldEntry = new LinkedHashMap<>();
                    fieldEntry.put("name", fieldName);
                    fieldEntry.put("type", field.path("type").asText(""));
                    String referenceTarget = field.path("reference").path("target").asText("");
                    if (!referenceTarget.isBlank()) {
                        fieldEntry.put("referenceTarget", referenceTarget);
                    }
                    fields.add(fieldEntry);
                }
            }
            conceptEntry.put("fields", fields);
            concepts.add(conceptEntry);
        }
        return concepts;
    }

    /** Best-effort local existence check for a forked-from pack's declared alias under packsDir. */
    private static Boolean packExists(Path packsDir, String forkedFromAlias) {
        if (packsDir == null || forkedFromAlias == null || forkedFromAlias.isBlank()) {
            return null;
        }
        return Files.isDirectory(packsDir.resolve(forkedFromAlias))
                && Files.isRegularFile(packsDir.resolve(forkedFromAlias).resolve("pack.json"));
    }

    private static Map<String, Object> forkedFrom(JsonNode forkedFromNode) {
        if (forkedFromNode == null || !forkedFromNode.isObject()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", forkedFromNode.path("pack").asText(""));
        out.put("version", forkedFromNode.path("version").asText(""));
        out.put("originAuthor", forkedFromNode.path("originAuthor").asText(""));
        return out;
    }

    private static Path locatePlatformPacksDir(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.isDirectory(current.resolve("NPDevContract"))
                    && Files.isDirectory(current.resolve("NPDevGenerator"))
                    && Files.isDirectory(current.resolve("NPDevKernel"))
                    && Files.isDirectory(current.resolve("NPDevRuntimeHost"))) {
                Path packsDir = current.resolve("NPDevContract").resolve("packs");
                return Files.isDirectory(packsDir) ? packsDir : null;
            }
            current = current.getParent();
        }
        return null;
    }
}
