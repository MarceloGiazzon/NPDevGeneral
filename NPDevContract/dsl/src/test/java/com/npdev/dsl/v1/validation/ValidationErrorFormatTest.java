package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationErrorFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void semanticValidationUsesNormalizedDiagnosticShape() throws Exception {
        Path modelPath = Files.createTempFile("npdev-step14-semantic-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "validation.semantic.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                        {
                          "name": "Patient",
                          "fields": [
                            { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "primaryProviderId", "type": "reference", "ref": "Provider", "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);

        assertTrue(result.hasErrors(), "Expected semantic validation errors.");
        assertFalse(result.getDiagnostics().isEmpty(), "Expected normalized semantic diagnostics.");

        ValidationDiagnostic diagnostic = result.getDiagnostics().get(0);
        assertTrue(result.getErrors().stream().allMatch(error -> !error.startsWith("Entity ")),
                "Semantic validation errors must use concept terminology.");
        assertEquals(ValidationLayer.SEMANTIC, diagnostic.getLayer());
        assertEquals(ValidationSeverity.ERROR, diagnostic.getSeverity());
        assertTrue(diagnostic.getMessage().startsWith("Concept "),
                "Semantic diagnostics must use concept terminology, got: " + diagnostic.getMessage());
        assertEquals("Patient", diagnostic.getConcept());
        assertEquals("primaryProviderId", diagnostic.getField());
        assertEquals("concepts", diagnostic.getSection());
        assertEquals("concepts[Patient].fields[primaryProviderId]", diagnostic.getPath());
        assertNotNull(diagnostic.getSuggestedFix());
        assertEquals("unknown_reference_target", diagnostic.getCode());
        assertTrue(diagnostic.getHelpKey().startsWith("validation.semantic."),
                "Expected semantic help key, got: " + diagnostic.getHelpKey());
    }

    @Test
    void structuralValidationUsesNormalizedDiagnosticShape() throws Exception {
        JsonNode malformedModel = MAPPER.readTree("""
                {
                  "namespace": "validation.structural.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "mrn", "required": true }
                      ]
                    }
                  ]
                }
                """);

        ValidationResult result = new JsonModelSchemaValidator().validateWithDiagnostics(malformedModel, "inline-model");

        assertTrue(result.hasErrors(), "Expected structural validation errors.");
        assertFalse(result.getDiagnostics().isEmpty(), "Expected normalized structural diagnostics.");

        ValidationDiagnostic diagnostic = result.getDiagnostics().get(0);
        assertEquals(ValidationLayer.STRUCTURAL, diagnostic.getLayer());
        assertEquals(ValidationSeverity.ERROR, diagnostic.getSeverity());
        assertTrue(diagnostic.getCode().startsWith("json_schema_"),
                "Expected structural code to be schema-prefixed, got: " + diagnostic.getCode());
        assertNotNull(diagnostic.getPath());
        assertNotNull(diagnostic.getSection());
        assertNotNull(diagnostic.getSuggestedFix());
        assertTrue(diagnostic.getHelpKey().startsWith("validation.structural."),
                "Expected structural help key, got: " + diagnostic.getHelpKey());
    }
}

