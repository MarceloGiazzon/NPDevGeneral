package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ModelAst;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedStructuresSupportTest {

    @Test
    void parserValidatorAndCompilerSupportNestedObjectsAndRepeatedSections() throws Exception {
        Path modelPath = Files.createTempFile("npdev-nested-structures-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "nested.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "emergencyContact",
                          "type": "object",
                          "properties": {
                            "name": { "type": "string" },
                            "phone": { "type": "string" }
                          },
                          "required": ["name", "phone"]
                        },
                        {
                          "name": "allergies",
                          "type": "array",
                          "minItems": 0,
                          "maxItems": 20,
                          "itemIdentityField": "code",
                          "duplicationPolicy": "deny",
                          "items": {
                            "type": "object",
                            "required": ["code", "substance"],
                            "properties": {
                              "code": { "type": "string" },
                              "substance": { "type": "string" },
                              "severity": { "type": "enum", "enumValues": ["Mild", "Severe"] }
                            }
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        FieldAst emergencyContact = ast.getConcepts().get(0).getFields().get(1);
        FieldAst allergies = ast.getConcepts().get(0).getFields().get(2);
        assertEquals("object", emergencyContact.getSchema().getType());
        assertEquals("array", allergies.getSchema().getType());
        assertEquals(20, allergies.getSchema().getMaxItems());
        assertEquals("code", allergies.getSchema().getItemIdentityField());

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected semantic validation to accept nested structures, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledField compiledAllergies = compiled.findConcept("Patient")
                .orElseThrow()
                .getFields()
                .stream()
                .filter(field -> "allergies".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertNotNull(compiledAllergies.getSchema());
        assertEquals("array", compiledAllergies.getSchema().getType());
        assertEquals(20, compiledAllergies.getSchema().getMaxItems());
        assertEquals("code", compiledAllergies.getSchema().getItemIdentityField());
        assertEquals("deny", compiledAllergies.getSchema().getDuplicationPolicy());
        assertNotNull(compiledAllergies.getSchema().getItems());
        assertEquals("object", compiledAllergies.getSchema().getItems().getType());
        assertTrue(compiledAllergies.getSchema().getItems().getProperties().containsKey("substance"));
    }

    @Test
    void semanticValidationRejectsBrokenNestedRequiredAndIdentityHints() throws Exception {
        Path modelPath = Files.createTempFile("npdev-nested-structures-error-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "nested.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "allergies",
                          "type": "array",
                          "minItems": 5,
                          "maxItems": 2,
                          "itemIdentityField": "missingCode",
                          "duplicationPolicy": "deny",
                          "items": {
                            "type": "object",
                            "required": ["code", "missingSubstance"],
                            "properties": {
                              "code": { "type": "string" }
                            }
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertFalse(errors.isEmpty(), "Expected semantic validation errors for broken nested structures.");
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("maxItems must be >= minItems")),
                "Expected cardinality validation error, got: " + errors
        );
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("itemIdentityField not found")),
                "Expected identity field validation error, got: " + errors
        );
    }
}

