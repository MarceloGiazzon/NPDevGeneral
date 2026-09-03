package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B16/B19 (Move 9 A3, {@code docs/ACCEPTED_BOUNDARIES.md}): a field's declared {@code picker.filter}/
 * {@code multiSelect} folds into the SAME {@code reference.defaultFilterExpression}/{@code multiple}
 * mechanism the generated app's picker dialog already sends server-side as a {@code where} query
 * param ({@code business-ui-app.mustache}'s {@code loadPickerRows}) and the generated CRUD controller
 * already enforces against real rows ({@code business-concept-crud-controller.mustache}'s
 * {@code applyWhere}/{@code parseWhereClauses}) -- proven end to end at the generation layer here;
 * the enforcement itself is unchanged, proven code this fix deliberately does not touch.
 *
 * <p>BOUNDARY_LIFT_PLAN_2026-09-02.md Wave 4 package 4.3 (B16) Step 1: {@code
 * defaultFilterExpression} is now always a JSON ARRAY of clauses (AND-combined via
 * {@link com.npdev.dsl.v1.query.PickerFilterGrammar}) instead of a single object, and a clause may
 * carry a {@code rootRef} (a {@code $root.<field>} reference resolved client-side against the
 * current record) instead of a static {@code value}.
 */
class BusinessUiEmitterPickerFilterTest {

    private static Path writeModel(String pickerBlock) throws IOException {
        Path modelPath = Files.createTempFile("npdev-picker-filter-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "picker.filter.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true },
                        { "name": "ativo", "type": "boolean", "required": true }
                      ]
                    },
                    {
                      "name": "Order",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "widgetRef",
                          "type": "reference",
                          "required": true,
                          "reference": { "target": "Widget", "displayField": "name" }
                          %s
                        }
                      ]
                    }
                  ]
                }
                """.formatted(pickerBlock));
        return modelPath;
    }

    private static JsonNode widgetRefReference(String pickerBlock) throws Exception {
        ModelAst ast = new JsonModelParser().parse(writeModel(pickerBlock));
        CompiledModel model = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-picker-filter-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        String manifest = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
        JsonNode root = new ObjectMapper().readTree(manifest);
        for (JsonNode concept : root.get("concepts")) {
            if (!"Order".equals(concept.get("conceptName").asText())) {
                continue;
            }
            for (JsonNode field : concept.get("fields")) {
                if ("widgetRef".equals(field.get("name").asText())) {
                    return field.get("reference");
                }
            }
        }
        throw new AssertionError("widgetRef field not found in generated-ui-manifest.json");
    }

    @Test
    void pickerFilterWithEqualsBecomesADefaultFilterExpression() throws Exception {
        JsonNode reference = widgetRefReference(", \"picker\": { \"filter\": \"ativo == true\" }");

        JsonNode expression = reference.get("defaultFilterExpression");
        assertTrue(expression != null && expression.isArray() && expression.size() == 1,
                "expected a one-clause defaultFilterExpression array derived from picker.filter");
        JsonNode clause = expression.get(0);
        assertEquals("ativo", clause.get("field").asText());
        assertEquals("eq", clause.get("operator").asText());
        assertEquals("true", clause.get("value").asText());
    }

    @Test
    void pickerFilterStripsARowPrefixAndQuotedStringLiteral() throws Exception {
        JsonNode reference = widgetRefReference(", \"picker\": { \"filter\": \"$row.name != 'Discontinued'\" }");

        JsonNode expression = reference.get("defaultFilterExpression");
        assertTrue(expression != null && expression.isArray() && expression.size() == 1);
        JsonNode clause = expression.get(0);
        assertEquals("name", clause.get("field").asText());
        assertEquals("ne", clause.get("operator").asText());
        assertEquals("Discontinued", clause.get("value").asText());
    }

    @Test
    void pickerFilterWithAndCombinesMultipleClauses() throws Exception {
        JsonNode reference = widgetRefReference(
                ", \"picker\": { \"filter\": \"ativo == true && name != 'Discontinued'\" }");

        JsonNode expression = reference.get("defaultFilterExpression");
        assertTrue(expression != null && expression.isArray() && expression.size() == 2,
                "expected two AND-combined clauses");
        assertEquals("ativo", expression.get(0).get("field").asText());
        assertEquals("name", expression.get(1).get("field").asText());
        assertEquals("ne", expression.get(1).get("operator").asText());
    }

    @Test
    void pickerFilterWithRootReferenceCarriesARootRefInsteadOfAValue() throws Exception {
        // Order (the FK field's own concept) has no field named "name" outside Widget, but it DOES
        // declare "widgetRef" -- so $root.widgetRef resolves against Order, the sourceConcept, since
        // there is no enclosing aggregate here.
        JsonNode reference = widgetRefReference(", \"picker\": { \"filter\": \"id == $root.widgetRef\" }");

        JsonNode expression = reference.get("defaultFilterExpression");
        assertTrue(expression != null && expression.isArray() && expression.size() == 1);
        JsonNode clause = expression.get(0);
        assertEquals("id", clause.get("field").asText());
        assertEquals("eq", clause.get("operator").asText());
        assertTrue(clause.get("value") == null || clause.get("value").isMissingNode(),
                "a $root reference clause must not carry a static value");
        assertEquals("widgetRef", clause.get("rootRef").asText());
    }

    @Test
    void pickerFilterWithRootReferenceToAnUndeclaredFieldDropsTheWholeFilter() throws Exception {
        JsonNode reference = widgetRefReference(", \"picker\": { \"filter\": \"id == $root.doesNotExist\" }");

        JsonNode expression = reference.get("defaultFilterExpression");
        assertTrue(expression == null || expression.isNull() || expression.isMissingNode(),
                "a $root reference to a field Order does not declare must silently drop the whole filter");
    }

    @Test
    void pickerMultiSelectSetsReferenceMultiple() throws Exception {
        JsonNode reference = widgetRefReference(", \"picker\": { \"multiSelect\": true }");

        assertTrue(reference.get("multiple").asBoolean(), "picker.multiSelect must set reference.multiple");
    }

    @Test
    void noPickerLeavesDefaultFilterExpressionAbsentAndMultipleFalse() throws Exception {
        JsonNode reference = widgetRefReference("");

        JsonNode expression = reference.get("defaultFilterExpression");
        assertTrue(expression == null || expression.isNull() || expression.isMissingNode(),
                "no picker declared -- no defaultFilterExpression should be synthesized");
        assertFalse(reference.get("multiple").asBoolean());
    }
}
