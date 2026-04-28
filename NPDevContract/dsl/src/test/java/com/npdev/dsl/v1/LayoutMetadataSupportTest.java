package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import com.npdev.dsl.v1.validation.ValidationLayer;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutMetadataSupportTest {

    @Test
    void parserCompilerAndValidatorSupportLayoutMetadata() throws Exception {
        Path modelPath = Files.createTempFile("npdev-layout-metadata-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "layout.metadata.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "ui": {
                        "label": "Appointment",
                        "formColumns": 2,
                        "displayMode": "standard",
                        "defaultSort": "-scheduledAt",
                        "defaultGroup": "status"
                      },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Scheduled", "Completed"], "ui": { "label": "Status" } },
                        {
                          "name": "scheduledAt",
                          "type": "datetime",
                          "ui": {
                            "label": "Scheduled at",
                            "tab": "Overview",
                            "column": 1,
                            "columnSpan": 1,
                            "width": "md",
                            "summaryCard": true,
                            "listColumn": true,
                            "listColumnOrder": 10
                          }
                        },
                        {
                          "name": "visitReason",
                          "type": "string",
                          "ui": {
                            "label": "Visit reason",
                            "tab": "Overview",
                            "column": 2,
                            "columnSpan": 1,
                            "width": "lg",
                            "displayMode": "compact"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        assertEquals(2, ast.getEntities().get(0).getUi().getFormColumns());
        assertEquals("Overview", ast.getEntities().get(0).getFields().get(2).getUi().getTab());

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no semantic errors, got: " + validation.getErrors());
        assertTrue(validation.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.getLayer() == ValidationLayer.UX_METADATA),
                "Expected no UX warnings for valid layout metadata.");

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(2, compiled.findEntity("Appointment").orElseThrow().getUi().getFormColumns());
        CompiledField scheduledAt = compiled.findEntity("Appointment")
                .orElseThrow()
                .getFields()
                .stream()
                .filter(field -> "scheduledAt".equals(field.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("Overview", scheduledAt.getUi().getTab());
        assertEquals(1, scheduledAt.getUi().getColumn());
        assertEquals("md", scheduledAt.getUi().getWidth());
        assertTrue(Boolean.TRUE.equals(scheduledAt.getUi().getSummaryCard()));
    }

    @Test
    void validatorWarnsOnBrokenLayoutMetadata() throws Exception {
        Path modelPath = Files.createTempFile("npdev-layout-metadata-error-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "layout.metadata.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "ui": {
                        "label": "Appointment",
                        "formColumns": 0,
                        "displayMode": "dense",
                        "defaultSort": "missingField",
                        "defaultGroup": "status"
                      },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Scheduled", "Completed"], "ui": { "label": "Status" } },
                        {
                          "name": "scheduledAt",
                          "type": "datetime",
                          "ui": {
                            "label": "Scheduled at",
                            "column": 0,
                            "width": "giant",
                            "listColumnOrder": 10
                          }
                        },
                        {
                          "name": "visitReason",
                          "type": "string",
                          "ui": {
                            "label": "Visit reason",
                            "column": 1,
                            "order": 10,
                            "listColumnOrder": 10,
                            "displayMode": "verbose"
                          }
                        },
                        {
                          "name": "notes",
                          "type": "string",
                          "ui": {
                            "label": "Notes",
                            "columnSpan": 2
                          }
                        },
                        {
                          "name": "followUpCode",
                          "type": "string",
                          "ui": {
                            "label": "Follow-up code",
                            "column": 1,
                            "order": 10
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no hard semantic errors, got: " + validation.getErrors());

        List<ValidationDiagnostic> uxWarnings = validation.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getLayer() == ValidationLayer.UX_METADATA)
                .toList();
        assertFalse(uxWarnings.isEmpty(), "Expected UX warnings for broken layout metadata.");
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "invalid_form_columns".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "invalid_display_mode".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "unknown_layout_field_ref".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "invalid_layout_column".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "incomplete_layout_slot".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "invalid_width_hint".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "duplicate_list_column_order".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "layout_slot_conflict".equals(diagnostic.getCode())));
    }
}

