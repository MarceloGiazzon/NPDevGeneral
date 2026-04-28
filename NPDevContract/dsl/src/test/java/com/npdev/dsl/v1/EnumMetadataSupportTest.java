package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledEnumOption;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnumMetadataSupportTest {

    @Test
    void parserValidatorAndCompilerSupportEnrichedEnumEntries() throws Exception {
        Path modelPath = Files.createTempFile("npdev-enum-metadata-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "enum.metadata.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "status",
                          "type": "enum",
                          "required": true,
                          "enumValues": [
                            {
                              "value": "Scheduled",
                              "label": "Scheduled",
                              "order": 10,
                              "group": "Active",
                              "default": true,
                              "iconHint": "calendar-clock",
                              "badge": "info",
                              "description": "Initial state"
                            },
                            {
                              "value": "Completed",
                              "label": "Completed",
                              "order": 20,
                              "group": "Terminal",
                              "badgeHint": "success"
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        assertEquals(2, ast.getEntities().get(0).getFields().get(1).getEnumOptions().size());
        assertEquals("Scheduled", ast.getEntities().get(0).getFields().get(1).getEnumOptions().get(0).getValue());

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected enriched enum metadata to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledField statusField = compiled.findEntity("Appointment")
                .orElseThrow()
                .getFields()
                .stream()
                .filter(field -> "status".equals(field.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("Scheduled", "Completed"), statusField.getEnumValues());
        assertEquals(2, statusField.getEnumOptions().size());
        CompiledEnumOption scheduled = statusField.getEnumOptions().get(0);
        assertEquals("Scheduled", scheduled.getValue());
        assertEquals("Scheduled", scheduled.getLabel());
        assertEquals("Active", scheduled.getGroup());
        assertTrue(scheduled.isDefaultValue());
        assertEquals("calendar-clock", scheduled.getIconHint());
        assertEquals("info", scheduled.getBadgeHint());
    }

    @Test
    void semanticValidationRejectsMultipleEnumDefaults() throws Exception {
        Path modelPath = Files.createTempFile("npdev-enum-metadata-error-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "enum.metadata.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "status",
                          "type": "enum",
                          "enumValues": [
                            { "value": "Scheduled", "default": true },
                            { "value": "Completed", "default": true }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertFalse(errors.isEmpty(), "Expected semantic validation to reject multiple defaults.");
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("at most one default value")),
                "Expected multiple-default enum error, got: " + errors
        );
    }
}

