package com.npdev.generator.provenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * S14 (NPDEV_MEGA_ROADMAP.md, Track B -- "the one new engine"): emits
 * {@code npdev/provenance-index.json} (contract {@code npdev-provenance-index.v1}, schema
 * {@code schemas/ai/provenance-index.schema.json}), the spec-node → emitted-artifact map that
 * {@code model-xref.json} (semantic edges, model→model) never had. For each persisted concept it
 * records the generated Spring classes, the schema migration, and the frontend route derived from
 * it -- so a query "what did this spec node produce?" has one answer, and Sessions 15 (impact
 * graph explorer) and 16 (diff/blast radius) have their graph to walk.
 *
 * <p>Join key: a {@code specNodes} key is the concept's qualified name, spelled exactly as
 * {@code model-xref.json}'s {@code edges[].toName} spells it for a {@code toKind: "concept"} edge,
 * and exactly as {@code box-manifest.json}'s {@code boxes[].graphName} spells it for a box with
 * {@code graphKind: "concept"} -- the three documents describe the same node under the same name,
 * so a consumer can join them without renormalizing anything.
 *
 * <p>Deterministic by construction: no timestamps, stable ordering (spec node, then artifact type
 * + path), and every artifact's digest is the SHA-256 of its emitted bytes. Re-running generation
 * therefore yields a byte-identical index -- the same guarantee
 * {@code check-deterministic-generation.ps1} already enforces on the tree via
 * {@code generated-folder.signature.properties}.
 *
 * <p>Artifact discovery is convention-based over the emitted output root (entity/dto/service/
 * controller class per {@link CompiledConcept#getClassName()}; {@code CREATE TABLE <table>} inside
 * the Flyway V1 schema-realization script; {@code "route" : "/<table>"} inside the generated UI
 * manifest). A file that does not exist is simply omitted -- the index records what WAS produced,
 * never fabricates a missing artifact.
 */
public final class ProvenanceIndexEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/npdev/provenance-index.json";
    private static final String SCHEMA_VERSION = "npdev-provenance-index.v1";
    private static final String ALGORITHM = "SHA-256";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Paths resolved relative to the generated app root. */
    private static final String ENTITY_DIR = "src/main/java/com/npdev/generated/entities";
    private static final String DTO_DIR = "src/main/java/com/npdev/generated/dtos";
    private static final String SERVICE_DIR = "src/main/java/com/npdev/generated/services";
    private static final String CONTROLLER_DIR = "src/main/java/com/npdev/generated/controllers";
    private static final String SCHEMA_SCRIPT = "src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql";
    private static final String UI_MANIFEST = "src/main/resources/static/npdev-business-ui/generated-ui-manifest.json";

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?");

    public void emit(CompiledModel model, Path generatedRoot) throws IOException {
        if (model == null || generatedRoot == null || !Files.isDirectory(generatedRoot)) {
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);

        String schemaScript = readIfPresent(generatedRoot.resolve(SCHEMA_SCRIPT));
        String uiManifest = readIfPresent(generatedRoot.resolve(UI_MANIFEST));

        Map<String, Object> specNodes = new LinkedHashMap<>();
        for (CompiledConcept concept : sortedConcepts(model)) {
            if (concept == null || concept.getName() == null || concept.getName().isBlank()) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("kind", "concept");
            String table = SqlIdentifierSupport.tableName(concept);
            node.put("table", table);

            List<Map<String, Object>> artifacts = new ArrayList<>();
            addIfPresent(generatedRoot, artifacts, "generated-class", entityPath(concept), null);
            addIfPresent(generatedRoot, artifacts, "generated-class", dtoPath(concept, "CreateRequest"), null);
            addIfPresent(generatedRoot, artifacts, "generated-class", dtoPath(concept, "UpdateRequest"), null);
            addIfPresent(generatedRoot, artifacts, "generated-class", dtoPath(concept, "Response"), null);
            addIfPresent(generatedRoot, artifacts, "generated-class", servicePath(concept, "ServiceBase"), null);
            addIfPresent(generatedRoot, artifacts, "generated-class", servicePath(concept, "Service"), null);
            addIfPresent(generatedRoot, artifacts, "generated-class", controllerPath(concept, "ControllerBase"), null);
            addIfPresent(generatedRoot, artifacts, "generated-class", controllerPath(concept, "Controller"), null);
            addIfPresent(generatedRoot, artifacts, "schema-migration", SCHEMA_SCRIPT,
                    schemaScript != null && containsCreateTable(schemaScript, table)
                            ? "CREATE TABLE " + table : null);
            addIfPresent(generatedRoot, artifacts, "frontend-route", UI_MANIFEST,
                    uiManifest != null && containsRoute(uiManifest, table)
                            ? "/" + table : null);

            artifacts.sort(Comparator.comparing(
                    (Map<String, Object> a) -> (String) a.get("type"))
                    .thenComparing(a -> (String) a.get("path")));
            node.put("artifacts", artifacts);
            specNodes.put(concept.getName(), node);
        }
        root.put("specNodes", specNodes);
        root.put("nodeCount", specNodes.size());

        Path out = generatedRoot.resolve(RELATIVE_PATH);
        Files.createDirectories(out.getParent());
        Files.writeString(out, OBJECT_MAPPER.writeValueAsString(root) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static List<CompiledConcept> sortedConcepts(CompiledModel model) {
        List<CompiledConcept> concepts = new ArrayList<>(model.getConcepts());
        concepts.sort(Comparator.comparing(CompiledConcept::getName));
        return concepts;
    }

    private static void addIfPresent(
            Path root,
            List<Map<String, Object>> artifacts,
            String type,
            String relativePath,
            String note
    ) {
        String digest = sha256IfPresent(root.resolve(relativePath));
        if (digest == null) {
            return;
        }
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("type", type);
        artifact.put("path", relativePath);
        artifact.put("digest", digest);
        if (note != null && !note.isBlank()) {
            artifact.put("note", note);
        }
        artifacts.add(artifact);
    }

    private static String entityPath(CompiledConcept concept) {
        return ENTITY_DIR + "/" + concept.getClassName() + ".java";
    }

    private static String dtoPath(CompiledConcept concept, String suffix) {
        return DTO_DIR + "/" + concept.getClassName() + suffix + ".java";
    }

    private static String servicePath(CompiledConcept concept, String suffix) {
        return SERVICE_DIR + "/" + concept.getClassName() + suffix + ".java";
    }

    private static String controllerPath(CompiledConcept concept, String suffix) {
        return CONTROLLER_DIR + "/" + concept.getClassName() + suffix + ".java";
    }

    private static String readIfPresent(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static String sha256IfPresent(Path path) {
        try {
            return Files.isRegularFile(path) ? sha256(Files.readAllBytes(path)) : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static boolean containsCreateTable(String schemaSql, String table) {
        int from = 0;
        java.util.regex.Matcher matcher = CREATE_TABLE_PATTERN.matcher(schemaSql);
        while (matcher.find()) {
            int nameStart = matcher.end();
            int nameEnd = schemaSql.indexOf('(', nameStart);
            if (nameEnd < 0) {
                return false;
            }
            String name = schemaSql.substring(nameStart, nameEnd).replace('"', ' ').trim()
                    .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (name.equals(table.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRoute(String uiManifest, String table) {
        return uiManifest.contains("\"route\" : \"/" + table + "\"")
                || uiManifest.contains("\"route\": \"/" + table + "\"");
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}