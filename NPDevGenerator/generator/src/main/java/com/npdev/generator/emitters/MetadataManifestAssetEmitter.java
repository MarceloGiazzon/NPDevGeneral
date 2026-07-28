package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledMetadataCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class MetadataManifestAssetEmitter {

    private static final String OUTPUT_ROOT = "src/main/resources/npdev/metadata/";
    private static final String GENERATED_FROM = "npdev/compiled-metadata.json";
    private static final String MANIFEST_VERSION = "1.0.0";

    private final GeneratedSourceWriter writer;
    private final ObjectMapper objectMapper;

    public MetadataManifestAssetEmitter(GeneratedSourceWriter writer) {
        this(writer, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));
    }

    MetadataManifestAssetEmitter(GeneratedSourceWriter writer, ObjectMapper objectMapper) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void emit(CompiledModel model, Path modelSourcePath) throws Exception {
        emit(model, null, modelSourcePath);
    }

    public void emit(CompiledModel model, ResolvedModelSource resolvedModelSource, Path modelSourcePath) throws Exception {
        JsonNode metadataRoot = objectMapper.readTree(resolvedModelSource == null
                ? CompiledMetadataCanonicalJson.toJson(modelSourcePath, model)
                : CompiledMetadataCanonicalJson.toJson(resolvedModelSource.resolvedRoot(), model));
        JsonNode catalogsNode = metadataRoot.path("catalogs");
        String metadataVersion = metadataRoot.path("metadataVersion").asText("1.0.0");

        List<CatalogDefinition> definitions = List.of(
                new CatalogDefinition("concepts", "concepts", "concepts.manifest.json"),
                new CatalogDefinition("procedures", "procedures", "procedures.manifest.json"),
                new CatalogDefinition("panels", "panels", "panels.manifest.json"),
                new CatalogDefinition("fields", "fields", "fields.manifest.json"),
                new CatalogDefinition("enums", "enums", "enums.manifest.json"),
                new CatalogDefinition("references", "references", "references.manifest.json"),
                new CatalogDefinition("actions", "actions", "actions.manifest.json"),
                new CatalogDefinition("transitions", "transitions", "transitions.manifest.json"),
                new CatalogDefinition("layout", "layout", "layout.manifest.json"),
                new CatalogDefinition("validationHints", "validation", "validation-hints.manifest.json"),
                // F2.2 (docs/NEXT_EXECUTION_PLAN.md P4.2): the invocations catalog (F2.1) was added to
                // CompiledMetadataCanonicalJson's catalogs object but never split out here, so
                // RuntimeMetadataService.catalog("invocations", ...) had no manifest to load -- the
                // bundle endpoint's `invocations` array would have 404'd. Same gap existed for
                // "transitions" (present in compiled-metadata.json before this session, never split).
                new CatalogDefinition("invocations", "invocations", "invocations.manifest.json")
        );

        ArrayNode indexCatalogs = JsonNodeFactory.instance.arrayNode();
        for (CatalogDefinition definition : definitions) {
            ArrayNode items = toArray(catalogsNode.get(definition.sourceCatalog()));
            ObjectNode manifestRoot = JsonNodeFactory.instance.objectNode();
            manifestRoot.put("metadataManifestVersion", MANIFEST_VERSION);
            manifestRoot.put("metadataVersion", metadataVersion);
            manifestRoot.put("catalog", definition.catalogName());
            manifestRoot.put("sourceCatalog", definition.sourceCatalog());
            manifestRoot.put("generatedFrom", GENERATED_FROM);
            manifestRoot.put("count", items.size());
            manifestRoot.set("items", items);
            writer.writeRelative(
                    OUTPUT_ROOT + definition.fileName(),
                    objectMapper.writeValueAsString(manifestRoot) + System.lineSeparator()
            );

            ObjectNode indexEntry = JsonNodeFactory.instance.objectNode();
            indexEntry.put("name", definition.catalogName());
            indexEntry.put("sourceCatalog", definition.sourceCatalog());
            indexEntry.put("path", "npdev/metadata/" + definition.fileName());
            indexEntry.put("count", items.size());
            indexCatalogs.add(indexEntry);
        }

        ObjectNode indexRoot = JsonNodeFactory.instance.objectNode();
        indexRoot.put("metadataManifestVersion", MANIFEST_VERSION);
        indexRoot.put("metadataVersion", metadataVersion);
        indexRoot.put("generatedFrom", GENERATED_FROM);
        indexRoot.set("catalogs", indexCatalogs);
        writer.writeRelative(
                OUTPUT_ROOT + "index.json",
                objectMapper.writeValueAsString(indexRoot) + System.lineSeparator()
        );
    }

    private static ArrayNode toArray(JsonNode node) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        if (node == null || !node.isArray()) {
            return array;
        }
        for (JsonNode item : node) {
            array.add(item.deepCopy());
        }
        return array;
    }

    private record CatalogDefinition(String catalogName, String sourceCatalog, String fileName) {
    }
}
