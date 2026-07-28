package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 Producer 1 (docs/NEXT_EXECUTION_PLAN.md P4.3): {@link CompiledMetadataCanonicalJson}'s
 * {@code panels[].provenance} for AutoPanel-expanded panels. {@link AutoPanelExpanderTest}'s own
 * {@code slimConceptAutoPanelExpandsToThreeSurfaces} model is reused verbatim -- a real
 * {@code CompiledPanel} produced by the real expander, not a hand-built fixture, so this exercises
 * the exact {@code metadata.generatedBy}/{@code concept} stamps {@link AutoPanelExpander} sets.
 */
class CompiledPanelProvenanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode panelsCatalog() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Cliente", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "nome", "type": "string" },
                  { "name": "email", "type": "string" } ] }
              ],
              "autoPanels": [ { "concept": "Cliente" } ]
            }
            """;
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        var compiled = new ModelCompiler().compile(ast);
        String metadataJson = CompiledMetadataCanonicalJson.toJson(compiled);
        return MAPPER.readTree(metadataJson).path("catalogs").path("panels");
    }

    private static JsonNode panel(JsonNode panels, String name) {
        for (JsonNode entry : panels) {
            if (name.equals(entry.path("name").asText())) {
                return entry;
            }
        }
        throw new AssertionError("panel not found: " + name);
    }

    @Test
    void autoPanelExpandedPanelsCarryConfirmedGeneratorProvenance() throws Exception {
        JsonNode panels = panelsCatalog();

        JsonNode selection = panel(panels, "ClienteSelection").path("provenance");
        assertEquals("npdev-panel-provenance.v1", selection.path("schemaVersion").asText());
        assertEquals("ClienteSelection", selection.path("panel").asText());
        assertEquals("generator", selection.path("producer").asText());
        assertEquals("autoPanel", selection.path("generatedFrom").path("generator").asText());
        assertTrue(selection.path("confirmed").asBoolean(), "generator output is confirmed by construction");
        assertTrue(selection.path("screenClass").isNull());
        assertTrue(selection.path("generatedFrom").path("modelHash").isMissingNode(),
                "modelHash is deliberately absent at model-compile time -- see this class's javadoc");
        assertEquals("panelAction:ClienteSelection:new", selection.path("invokes").get(0).asText());
        // Selection is a read-only table: every bound field is a read, none is a write.
        assertTrue(selection.path("reads").path(0).asText().startsWith("Cliente."));
        assertEquals(0, selection.path("writes").size());

        JsonNode form = panel(panels, "ClienteForm").path("provenance");
        assertEquals("generator", form.path("producer").asText());
        // Form fields are all editable -- id is excluded (dropped from the editable set upstream).
        assertEquals(2, form.path("writes").size());
        assertTrue(streamOf(form.path("writes")).anyMatch("Cliente.email"::equals));
        assertTrue(streamOf(form.path("writes")).anyMatch("Cliente.nome"::equals));
        assertTrue(streamOf(form.path("invokes")).anyMatch("panelAction:ClienteForm:save"::equals));
        assertTrue(streamOf(form.path("invokes")).anyMatch("panelAction:ClienteForm:delete"::equals));
    }

    @Test
    void handDeclaredPanelHasNoProvenance() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.hp", "version": "1.0",
              "concepts": [
                { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "label", "type": "string" } ] }
              ],
              "panels": [ {
                "name": "WidgetBoard",
                "route": "/widgets",
                "dataSources": [ { "name": "rows", "concept": "Widget" } ],
                "layout": { "type": "table", "fields": ["label"] }
              } ]
            }
            """;
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        var compiled = new ModelCompiler().compile(ast);
        JsonNode panels = MAPPER.readTree(CompiledMetadataCanonicalJson.toJson(compiled))
                .path("catalogs").path("panels");

        assertTrue(panel(panels, "WidgetBoard").path("provenance").isNull(),
                "a hand-declared panel (no generatedBy stamp) has no provenance to report");
    }

    private static java.util.stream.Stream<String> streamOf(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText);
    }
}
