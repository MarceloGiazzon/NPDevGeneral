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
        assertTrue(expression != null && !expression.isNull(), "expected a defaultFilterExpression to be derived from picker.filter");
        assertEquals("ativo", expression.get("field").asText());
        assertEquals("eq", expression.get("operator").asText());
        assertEquals("true", expression.get("value").asText());
    }

    @Test
    void pickerFilterStripsARowPrefixAndQuotedStringLiteral() throws Exception {
        JsonNode reference = widgetRefReference(", \"picker\": { \"filter\": \"$row.name != 'Discontinued'\" }");

        JsonNode expression = reference.get("defaultFilterExpression");
        assertTrue(expression != null && !expression.isNull());
        assertEquals("name", expression.get("field").asText());
        assertEquals("ne", expression.get("operator").asText());
        assertEquals("Discontinued", expression.get("value").asText());
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
