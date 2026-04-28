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

class InteractionMetadataSupportTest {

    @Test
    void parserCompilerAndValidatorSupportInteractionMetadata() throws Exception {
        Path modelPath = Files.createTempFile("npdev-interaction-metadata-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "interaction.metadata.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Provider",
                      "ui": { "label": "Provider" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "fullName", "type": "string", "required": true, "ui": { "label": "Provider name" } },
                        { "name": "specialty", "type": "string", "ui": { "label": "Specialty" } }
                      ]
                    },
                    {
                      "name": "Appointment",
                      "ui": { "label": "Appointment" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "status",
                          "type": "enum",
                          "enumValues": ["Scheduled", "CheckedIn", "Completed"],
                          "ui": { "label": "Status" }
                        },
                        {
                          "name": "providerId",
                          "type": "reference",
                          "reference": {
                            "target": "Provider",
                            "searchFields": ["fullName", "specialty"],
                            "inlineCreate": "deny"
                          },
                          "ui": {
                            "label": "Provider",
                            "visibleWhen": "status == 'Scheduled' || status == 'CheckedIn'",
                            "enabledWhen": "status == 'Scheduled'",
                            "readonlyWhen": "status == 'Completed'",
                            "requiredWhen": "status == 'Scheduled'",
                            "pickerType": "search-dialog",
                            "allowInlineCreate": false,
                            "searchFields": ["fullName", "specialty"],
                            "filterPreset": "available-providers"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        assertEquals("status == 'Scheduled'", ast.getEntities().get(1).getFields().get(2).getUi().getEnabledWhen());

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no semantic errors, got: " + validation.getErrors());
        assertTrue(validation.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.getLayer() == ValidationLayer.UX_METADATA),
                "Expected no UX warnings for valid interaction metadata.");

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledField providerField = compiled.findEntity("Appointment")
                .orElseThrow()
                .getFields()
                .stream()
                .filter(field -> "providerId".equals(field.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("status == 'Scheduled'", providerField.getUi().getEnabledWhen());
        assertEquals("status == 'Completed'", providerField.getUi().getReadonlyWhen());
        assertEquals("search-dialog", providerField.getUi().getPickerType());
        assertEquals(List.of("fullName", "specialty"), providerField.getUi().getSearchFields());
    }

    @Test
    void validatorWarnsOnBrokenInteractionMetadata() throws Exception {
        Path modelPath = Files.createTempFile("npdev-interaction-metadata-error-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "interaction.metadata.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Provider",
                      "ui": { "label": "Provider" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "fullName", "type": "string", "ui": { "label": "Provider name" } }
                      ]
                    },
                    {
                      "name": "Appointment",
                      "ui": { "label": "Appointment" },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Scheduled", "Completed"], "ui": { "label": "Status" } },
                        {
                          "name": "visitReason",
                          "type": "string",
                          "ui": {
                            "label": "Visit reason",
                            "pickerType": "search-dialog",
                            "visibleWhen": "status ==",
                            "searchFields": ["fullName", "fullName"]
                          }
                        },
                        {
                          "name": "providerId",
                          "type": "reference",
                          "reference": { "target": "Provider" },
                          "ui": {
                            "label": "Provider",
                            "enabledWhen": "missingField == 'x'",
                            "searchFields": ["missingField"]
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
        assertFalse(uxWarnings.isEmpty(), "Expected UX warnings for broken interaction metadata.");
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "invalid_interaction_condition".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "interaction_metadata_not_supported".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "duplicate_interaction_search_field".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "unknown_interaction_field_ref".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "unknown_interaction_search_field".equals(diagnostic.getCode())));
    }
}

