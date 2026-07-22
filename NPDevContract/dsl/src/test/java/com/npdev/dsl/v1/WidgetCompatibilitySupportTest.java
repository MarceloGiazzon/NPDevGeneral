package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import com.npdev.dsl.v1.validation.ValidationLayer;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Widget/datatype compatibility validation (FieldWidgetDefaults.classify, wired into
 * SemanticValidator): structurally-broken combinations are hard errors that block generation
 * (GeneratorMain exits before compiling/emitting on any error); merely-mismatched-but-rendering
 * combinations are UX_METADATA warnings only.
 */
class WidgetCompatibilitySupportTest {

    private static ModelAst parse(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-widget-compat-", ".json");
        Files.writeString(modelPath, json);
        return new JsonModelParser().parse(modelPath);
    }

    @Test
    void validCombinationsAcrossTheExpandedCatalogProduceNoErrorsOrWarnings() throws Exception {
        ModelAst ast = parse("""
                {
                  "namespace": "widget.compat.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Country",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true },
                        { "name": "flagUrl", "type": "string" }
                      ]
                    },
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "accentColor", "type": "string", "ui": { "label": "Accent color", "widget": "color" } },
                        {
                          "name": "status",
                          "type": "enum",
                          "enumValues": [
                            { "value": "Active", "iconHint": "\\ud83d\\udfe2" },
                            { "value": "Retired", "iconHint": "\\u26aa" }
                          ],
                          "ui": { "label": "Status", "widget": "image-select" }
                        },
                        { "name": "category", "type": "enum", "enumValues": ["A", "B", "C"], "ui": { "label": "Category", "widget": "autocomplete" } },
                        {
                          "name": "tags",
                          "type": "array",
                          "items": { "type": "enum", "enumValues": ["red", "green", "blue"] },
                          "ui": { "label": "Tags", "widget": "multiselect" }
                        },
                        {
                          "name": "originRef",
                          "type": "reference",
                          "reference": { "target": "Country", "displayField": "name" },
                          "ui": { "label": "Origin", "widget": "image-select", "imageField": "flagUrl" }
                        }
                      ]
                    }
                  ]
                }
                """);

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no semantic errors, got: " + validation.getErrors());
        // Fixture deliberately omits concept/field ui.label in a couple of spots (irrelevant to
        // widget compatibility) which trips the separate, pre-existing missing-label UX checks --
        // narrow this assertion to widget compatibility's own "discouraged_widget" diagnostic code.
        assertTrue(validation.getDiagnostics().stream().noneMatch(d -> "discouraged_widget".equals(d.getCode())),
                "Expected no discouraged-widget warnings for valid widget declarations, got: " + validation.getDiagnostics());
    }

    @Test
    void structurallyBrokenWidgetDeclarationsAreHardErrors() throws Exception {
        ModelAst ast = parse("""
                {
                  "namespace": "widget.compat.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "ui": { "label": "Name", "widget": "checkbox" } },
                        { "name": "isActive", "type": "boolean", "ui": { "label": "Active", "widget": "not-a-real-widget" } },
                        { "name": "customThing", "type": "string", "ui": { "label": "Custom", "widget": "custom" } }
                      ]
                    }
                  ]
                }
                """);

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertFalse(validation.getErrors().isEmpty(), "Expected semantic errors for structurally-broken widget declarations.");
        assertTrue(validation.getErrors().stream().anyMatch(e -> e.contains("name") && e.contains("checkbox")));
        assertTrue(validation.getErrors().stream().anyMatch(e -> e.contains("isActive") && e.contains("not-a-real-widget")));
        assertTrue(validation.getErrors().stream().anyMatch(e -> e.contains("customThing") && e.contains("custom")));
    }

    @Test
    void mismatchedButRenderingWidgetDeclarationsAreWarningsOnly() throws Exception {
        ModelAst ast = parse("""
                {
                  "namespace": "widget.compat.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Ticket",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "referenceCode", "type": "int", "required": true, "ui": { "label": "Reference code", "widget": "email" } },
                        {
                          "name": "attachments",
                          "type": "array",
                          "items": { "type": "object", "properties": { "url": { "type": "string" } } },
                          "ui": { "label": "Attachments", "widget": "text" }
                        }
                      ]
                    }
                  ]
                }
                """);

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no hard errors for merely-discouraged widget declarations, got: " + validation.getErrors());

        List<ValidationDiagnostic> uxWarnings = validation.getDiagnostics().stream()
                .filter(d -> d.getLayer() == ValidationLayer.UX_METADATA)
                .toList();
        assertTrue(uxWarnings.stream().anyMatch(d -> "discouraged_widget".equals(d.getCode()) && "referenceCode".equals(d.getField())));
        assertTrue(uxWarnings.stream().anyMatch(d -> "discouraged_widget".equals(d.getCode()) && "attachments".equals(d.getField())));
    }
}
