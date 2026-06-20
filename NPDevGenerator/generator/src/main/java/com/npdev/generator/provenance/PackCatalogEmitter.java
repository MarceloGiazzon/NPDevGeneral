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
        writer.writeRelative(RELATIVE_PATH, toJson(internalTablesEnabled));
    }

    private static String toJson(boolean internalTablesEnabled) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "npdev-pack-catalog.v1");
        Path packsDir = locatePlatformPacksDir(Path.of("").toAbsolutePath().normalize());
        root.put("discoverable", packsDir != null);
        root.put("packs", packsDir == null ? List.of() : readCatalog(packsDir, internalTablesEnabled));
        try {
            return OBJECT_MAPPER.writeValueAsString(root) + System.lineSeparator();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize pack catalog", exception);
        }
    }

    private static List<Map<String, Object>> readCatalog(Path packsDir, boolean internalTablesEnabled) {
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
                packs.add(readPackEntry(packDir.getFileName().toString(), packJson, internalTablesEnabled));
            }
        } catch (IOException exception) {
            return List.of();
        }
        return packs;
    }

    private static Map<String, Object> readPackEntry(String alias, Path packJson, boolean internalTablesEnabled) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("alias", alias);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(packJson.toFile());
            entry.put("name", root.path("pack").asText(alias));
            entry.put("version", root.path("version").asText("UNKNOWN"));
            entry.put("description", root.path("description").asText(""));
            entry.put("conceptCount", root.path("concepts").isArray() ? root.path("concepts").size() : 0);
        } catch (IOException exception) {
            entry.put("name", alias);
            entry.put("version", "UNKNOWN");
            entry.put("description", "");
            entry.put("conceptCount", 0);
        }
        entry.put("included", internalTablesEnabled && BuiltinPackComposer.BUILTIN_PACK_ALIASES.contains(alias));
        return entry;
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
