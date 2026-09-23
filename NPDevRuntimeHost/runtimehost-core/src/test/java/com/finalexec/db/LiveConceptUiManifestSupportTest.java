package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-244 Phase 4D: {@link LiveConceptUiManifestSupport} appends a manifest node for a brand-new,
 * already-provisioned concept to an on-disk {@code generated-ui-manifest.json} -- additive only,
 * idempotent, every failure named rather than dropped or thrown. No Spring/DB needed, unlike the
 * sibling 4B/4C tests, since this class only ever touches a CompiledModel and a JSON file.
 */
class LiveConceptUiManifestSupportTest {

    @TempDir
    Path tempDir;

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-ui-manifest-support-", ".json");
        Files.writeString(modelPath, json);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new ModelCompiler().compile(ast);
    }

    private Path writeManifest(String json) throws Exception {
        Path file = tempDir.resolve("generated-ui-manifest.json");
        Files.writeString(file, json);
        return file;
    }

    private static final String EXISTING_MANIFEST = """
            { "schemaVersion": "npdev-generated-ui-manifest.v1", "defaultGuidePage": "Default",
              "concepts": [ { "conceptName": "Thing", "route": "/things" } ] }
            """;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readManifest(Path file) throws Exception {
        return new ObjectMapper().readValue(file.toFile(), Map.class);
    }

    @Test
    void appendsNodeForNewConceptAndLeavesExistingNodeUntouched() throws Exception {
        Path manifestFile = writeManifest(EXISTING_MANIFEST);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4d", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Thing", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] },
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "label", "type": "string", "required": true }
                  ] }
                ] }
                """);

        LiveConceptUiManifestSupport.Result result =
                LiveConceptUiManifestSupport.refresh(manifestFile, newModel, List.of("Widget"));

        assertEquals(List.of("Widget"), result.refreshed());
        assertTrue(result.failed().isEmpty(), "expected no failures, got " + result.failed());

        Map<String, Object> manifest = readManifest(manifestFile);
        List<Map<String, Object>> concepts = (List<Map<String, Object>>) (List<?>) manifest.get("concepts");
        assertEquals(2, concepts.size(), "expected Thing (untouched) + Widget (new): " + concepts);

        Map<String, Object> thing = concepts.get(0);
        assertEquals("/things", thing.get("route"), "existing node must be byte-for-byte untouched");
        assertEquals(2, thing.size(), "existing node must gain no new keys");

        Map<String, Object> widget = concepts.stream().filter(c -> "Widget".equals(c.get("conceptName"))).findFirst().orElseThrow();
        assertEquals("/widgets", widget.get("route"));
        assertEquals("widgets", widget.get("tableName"));
        assertEquals("id", widget.get("idField"));
        assertEquals("Default", widget.get("guidePage"), "an unconfigured concept falls through to the manifest's own default");
        assertEquals("full", widget.get("frameMode"));
        assertEquals(Boolean.FALSE, widget.get("admin"));
        List<Map<String, Object>> fields = (List<Map<String, Object>>) (List<?>) widget.get("fields");
        assertEquals(2, fields.size());
        Map<String, Object> labelField = fields.stream().filter(f -> "label".equals(f.get("name"))).findFirst().orElseThrow();
        assertEquals("string", labelField.get("type"));
        assertEquals("text", labelField.get("widget"), "a plain string field must default to a text input, never the reference widget");
    }

    @Test
    void refreshIsIdempotentOnRepeatedCall() throws Exception {
        Path manifestFile = writeManifest(EXISTING_MANIFEST);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4d", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Thing", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);

        LiveConceptUiManifestSupport.Result first =
                LiveConceptUiManifestSupport.refresh(manifestFile, newModel, List.of("Widget"));
        LiveConceptUiManifestSupport.Result second =
                LiveConceptUiManifestSupport.refresh(manifestFile, newModel, List.of("Widget"));

        assertEquals(List.of("Widget"), first.refreshed());
        // Same idempotency posture as NewConceptSchemaProvisioner's own IF NOT EXISTS DDL: the
        // postcondition already holds, so the second call reports success, not failure.
        assertEquals(List.of("Widget"), second.refreshed());
        assertTrue(second.failed().isEmpty());

        Map<String, Object> manifest = readManifest(manifestFile);
        List<?> concepts = (List<?>) manifest.get("concepts");
        assertEquals(2, concepts.size(), "a second call must not duplicate the node");
    }

    @Test
    void missingManifestFileFailsEveryNameWithoutThrowing() throws Exception {
        Path manifestFile = tempDir.resolve("does-not-exist.json");
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4d", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);

        LiveConceptUiManifestSupport.Result result =
                LiveConceptUiManifestSupport.refresh(manifestFile, newModel, List.of("Widget"));

        assertTrue(result.refreshed().isEmpty());
        assertTrue(result.failed().containsKey("Widget"));
        assertFalse(Files.exists(manifestFile), "no file must be created out of thin air");
    }

    @Test
    void conceptNotInModelFailsNamedWithoutThrowing() throws Exception {
        Path manifestFile = writeManifest(EXISTING_MANIFEST);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4d", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Thing", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);

        LiveConceptUiManifestSupport.Result result =
                LiveConceptUiManifestSupport.refresh(manifestFile, newModel, List.of("Ghost"));

        assertTrue(result.refreshed().isEmpty());
        assertTrue(result.failed().get("Ghost").contains("not found"), "" + result.failed());
    }

    @Test
    void enumAndNestedObjectFieldsAreFullyDescribed() throws Exception {
        Path manifestFile = writeManifest(EXISTING_MANIFEST);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4d", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Thing", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                  { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "status", "type": "enum", "enumValues": ["OPEN", "CLOSED"] },
                    { "name": "shipping", "type": "object", "properties": { "city": { "type": "string" } } }
                  ] }
                ] }
                """);

        LiveConceptUiManifestSupport.Result result =
                LiveConceptUiManifestSupport.refresh(manifestFile, newModel, List.of("Order"));

        assertTrue(result.failed().isEmpty(), "" + result.failed());
        Map<String, Object> manifest = readManifest(manifestFile);
        List<Map<String, Object>> concepts = (List<Map<String, Object>>) (List<?>) manifest.get("concepts");
        Map<String, Object> order = concepts.stream().filter(c -> "Order".equals(c.get("conceptName"))).findFirst().orElseThrow();
        List<Map<String, Object>> fields = (List<Map<String, Object>>) (List<?>) order.get("fields");

        Map<String, Object> status = fields.stream().filter(f -> "status".equals(f.get("name"))).findFirst().orElseThrow();
        assertEquals("select", status.get("widget"));
        assertEquals(List.of("OPEN", "CLOSED"), status.get("enumValues"));
        assertEquals(2, ((List<?>) status.get("enumOptions")).size());
        assertEquals("OrderStatus", status.get("enumName"));

        Map<String, Object> shipping = fields.stream().filter(f -> "shipping".equals(f.get("name"))).findFirst().orElseThrow();
        assertTrue(shipping.containsKey("objectSchema"), "expected a nested objectSchema node: " + shipping);
        assertFalse(shipping.containsKey("reference"), "an object field must never carry reference metadata");
    }

    @Test
    void emptyProvisionedListIsANoOp() throws Exception {
        Path manifestFile = writeManifest(EXISTING_MANIFEST);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4d", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Thing", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);

        LiveConceptUiManifestSupport.Result result =
                LiveConceptUiManifestSupport.refresh(manifestFile, newModel, List.of());

        assertTrue(result.refreshed().isEmpty());
        assertTrue(result.failed().isEmpty());
        Map<String, Object> manifest = readManifest(manifestFile);
        assertEquals(1, ((List<?>) manifest.get("concepts")).size(), "manifest must be unchanged");
    }
}
