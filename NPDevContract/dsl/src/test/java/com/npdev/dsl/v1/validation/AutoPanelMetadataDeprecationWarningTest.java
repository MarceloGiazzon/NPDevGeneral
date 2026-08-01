package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 8 D2 (item G4, docs/MOVE8_CLOSE_TABLE_SPEC.md): the six untyped
 * {@code autoPanel.transaction.metadata} keys Move 6/7 gave typed replacements to
 * ({@code recompute}, {@code derived}, {@code computed}, {@code actions}, {@code visibleWhen},
 * {@code bandPickers}) must each emit a deprecation WARNING (not an error) when still present, so
 * the untyped bag does not quietly become a permanent, silent-migration-free resting place.
 */
class AutoPanelMetadataDeprecationWarningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> warningsFor(String metadataJson) throws Exception {
        String modelJson = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.d2.deprecation", "version": "1.0",
              "concepts": [
                { "name": "Movimento", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "aggregates": [
                { "name": "Movimento", "root": "Movimento", "collections": [] }
              ],
              "autoPanels": [ { "aggregate": "Movimento",
                "transaction": { "metadata": %s } } ]
            }
            """.formatted(metadataJson);
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(modelJson));
        return new SemanticValidator().validateWithWarnings(ast).getWarnings();
    }

    @Test
    void noMetadataBagEmitsNoDeprecationWarnings() throws Exception {
        List<String> warnings = warningsFor("{}");
        assertFalse(warnings.stream().anyMatch(w -> w.contains("is deprecated")),
                "expected no deprecation warnings, got: " + warnings);
    }

    @Test
    void metadataComputedEmitsDeprecationWarning() throws Exception {
        List<String> warnings = warningsFor("{ \"computed\": [ { \"col\": \"total\", \"expr\": \"1 + 1\" } ] }");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("transaction.metadata.computed is deprecated")
                        && w.contains("computed[]") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.computed deprecation warning, got: " + warnings);
    }

    @Test
    void metadataActionsEmitsDeprecationWarning() throws Exception {
        List<String> warnings = warningsFor("{ \"actions\": [ { \"procedure\": \"Anything\" } ] }");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("transaction.metadata.actions is deprecated")
                        && w.contains("transaction.actions") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.actions deprecation warning, got: " + warnings);
    }

    @Test
    void metadataVisibleWhenEmitsDeprecationWarning() throws Exception {
        List<String> warnings = warningsFor("{ \"visibleWhen\": { \"header\": \"true\" } }");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("transaction.metadata.visibleWhen is deprecated")
                        && w.contains("transaction.visibleWhen") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.visibleWhen deprecation warning, got: " + warnings);
    }

    @Test
    void metadataBandPickersEmitsDeprecationWarning() throws Exception {
        List<String> warnings = warningsFor("{ \"bandPickers\": { \"x\": { \"panel\": \"Y\" } } }");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("transaction.metadata.bandPickers is deprecated")
                        && w.contains("transaction.bandPickers") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.bandPickers deprecation warning, got: " + warnings);
    }
}
