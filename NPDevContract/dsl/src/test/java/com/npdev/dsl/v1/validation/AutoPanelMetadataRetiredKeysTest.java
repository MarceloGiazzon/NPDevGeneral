package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six untyped {@code autoPanel.transaction.metadata} keys Move 6/7 gave typed replacements to
 * ({@code recompute}, {@code derived}, {@code computed}, {@code actions}, {@code visibleWhen},
 * {@code bandPickers}) are RETIRED: each is a validation ERROR naming its typed replacement and
 * {@code npdev migrate dsl-2}. They used to be read as a fallback and only warn; the compiler no
 * longer reads them, so a leftover key must fail loudly instead of silently doing nothing.
 */
class AutoPanelMetadataRetiredKeysTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> errorsFor(String metadataJson) throws Exception {
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
        return new SemanticValidator().validateWithWarnings(ast).getErrors();
    }

    @Test
    void noMetadataBagEmitsNoRetiredKeyErrors() throws Exception {
        List<String> errors = errorsFor("{}");
        assertFalse(errors.stream().anyMatch(w -> w.contains("is retired")),
                "expected no retired-key errors, got: " + errors);
    }

    @Test
    void metadataComputedEmitsRetiredError() throws Exception {
        List<String> errors = errorsFor("{ \"computed\": [ { \"col\": \"total\", \"expr\": \"1 + 1\" } ] }");
        assertTrue(errors.stream().anyMatch(w -> w.contains("transaction.metadata.computed is retired")
                        && w.contains("computed[]") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.computed retired-key error, got: " + errors);
    }

    @Test
    void metadataActionsEmitsRetiredError() throws Exception {
        List<String> errors = errorsFor("{ \"actions\": [ { \"procedure\": \"Anything\" } ] }");
        assertTrue(errors.stream().anyMatch(w -> w.contains("transaction.metadata.actions is retired")
                        && w.contains("transaction.actions") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.actions retired-key error, got: " + errors);
    }

    @Test
    void metadataVisibleWhenEmitsRetiredError() throws Exception {
        List<String> errors = errorsFor("{ \"visibleWhen\": { \"header\": \"true\" } }");
        assertTrue(errors.stream().anyMatch(w -> w.contains("transaction.metadata.visibleWhen is retired")
                        && w.contains("transaction.visibleWhen") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.visibleWhen retired-key error, got: " + errors);
    }

    @Test
    void metadataBandPickersEmitsRetiredError() throws Exception {
        List<String> errors = errorsFor("{ \"bandPickers\": { \"x\": { \"panel\": \"Y\" } } }");
        assertTrue(errors.stream().anyMatch(w -> w.contains("transaction.metadata.bandPickers is retired")
                        && w.contains("transaction.bandPickers") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.bandPickers retired-key error, got: " + errors);
    }

    @Test
    void metadataRecomputeIsARetiredKeyError() throws Exception {
        List<String> errors = errorsFor("{ \"recompute\": \"Anything\" }");
        assertTrue(errors.stream().anyMatch(w -> w.contains("transaction.metadata.recompute is retired")
                        && w.contains("transaction.hooks.onFieldChange") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.recompute retired-key error, got: " + errors);
    }

    @Test
    void metadataDerivedIsARetiredKeyError() throws Exception {
        List<String> errors = errorsFor("{ \"derived\": [ { \"name\": \"x\", \"expression\": \"sum(a[].b)\" } ] }");
        assertTrue(errors.stream().anyMatch(w -> w.contains("transaction.metadata.derived is retired")
                        && w.contains("transaction.derivedFields") && w.contains("npdev migrate dsl-2")),
                "expected a metadata.derived retired-key error, got: " + errors);
    }
}
