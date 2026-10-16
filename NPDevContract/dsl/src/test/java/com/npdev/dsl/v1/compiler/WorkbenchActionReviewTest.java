package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI-7: a workbench action declared with {@code review: true} keeps the flag through parse ->
 * compile -> workbench descriptor -> canonical JSON round trip; an action that never declared it
 * stays byte-identical (no {@code review} key anywhere); and the validator refuses combining it
 * with the branches the review dialog does not handle.
 */
class WorkbenchActionReviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.review", "version": "1.0",
              "concepts": [
                { "name": "Cap", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "finish", "type": "string" } ] }
              ],
              "aggregates": [ { "name": "Cap", "root": "Cap" } ],
              "procedures": [
                { "name": "Identify", "steps": [ { "name": "r", "type": "return", "value": "$input" } ] },
                { "name": "Fold", "steps": [ { "name": "r", "type": "return", "value": "$input" } ] }
              ],
              "autoPanels": [ { "aggregate": "Cap",
                "transaction": { "actions": [ %s ] } } ]
            }
            """;

    private static ModelAst parse(String actions) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(MODEL.formatted(actions)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> descriptorActions(CompiledModel model) {
        CompiledPanel workbench = model.getPanels().stream()
                .filter(p -> "CapWorkbench".equals(p.name())).findFirst()
                .orElseThrow(() -> new AssertionError("expected CapWorkbench"));
        Map<String, Object> wb = (Map<String, Object>) workbench.metadata().get("workbench");
        return (List<Map<String, Object>>) wb.get("actions");
    }

    @Test
    void reviewFlagSurvivesCompileDescriptorAndCanonicalRoundTrip() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse("""
                { "procedure": "Identify", "label": "Identify with AI", "review": true },
                { "procedure": "Fold", "label": "Plain" }
                """));

        List<Map<String, Object>> actions = descriptorActions(compiled);
        assertEquals(Boolean.TRUE, actions.get(0).get("review"));
        assertNull(actions.get(1).get("review"), "an action that never declared review has no key");

        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledModel back = CompiledModelCanonicalJsonReader.fromJson(json);
        assertEquals(Boolean.TRUE, descriptorActions(back).get(0).get("review"));
        assertNull(descriptorActions(back).get(1).get("review"));
        assertEquals(json, CompiledModelCanonicalJson.toJson(back), "canonical JSON stays idempotent");
    }

    @Test
    void absentReviewLeavesCanonicalJsonFreeOfTheKey() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse("""
                { "procedure": "Identify", "label": "Identify with AI" }
                """));
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("\"review\""),
                "review is written only when true, so existing compiled output is unchanged");
    }

    @Test
    void reviewCannotBeCombinedWithAfterAction() throws Exception {
        List<String> errors = new SemanticValidator().validate(parse("""
                { "procedure": "Identify", "afterAction": "Fold", "review": true }
                """));
        assertTrue(errors.stream().anyMatch(e ->
                        e.contains("review cannot be combined with applyTo, afterAction or checkBalances")),
                "Expected the review/afterAction conflict, got: " + errors);
    }

    @Test
    void reviewAloneIsAccepted() throws Exception {
        List<String> errors = new SemanticValidator().validate(parse("""
                { "procedure": "Identify", "label": "Identify with AI", "review": true }
                """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("review cannot be combined")),
                "A plain review action must not trip the conflict rule: " + errors);
    }
}
