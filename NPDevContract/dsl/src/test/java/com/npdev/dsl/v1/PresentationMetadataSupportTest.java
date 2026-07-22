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
import com.npdev.dsl.v1.validation.ValidationSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationMetadataSupportTest {

    @Test
    void parserCompilerAndValidatorSupportPresentationMetadata() throws Exception {
        Path modelPath = Files.createTempFile("npdev-presentation-metadata-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "presentation.metadata.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "ui": {
                        "label": "Patient",
                        "shortLabel": "Pt",
                        "description": "Patient profile",
                        "group": "Clinical operations",
                        "section": "Registration",
                        "order": 10,
                        "examples": ["New patient intake"]
                      },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "firstName",
                          "type": "string",
                          "ui": {
                            "label": "First name",
                            "shortLabel": "First",
                            "description": "Given name",
                            "helpText": "Use the preferred given name",
                            "placeholder": "Marina",
                            "group": "Identity",
                            "section": "Registration",
                            "order": 10,
                            "examples": ["Marina"],
                            "widget": "text"
                          }
                        },
                        {
                          "name": "lastName",
                          "type": "string",
                          "ui": {
                            "label": "Last name",
                            "group": "Identity",
                            "section": "Registration",
                            "order": 20,
                            "widget": "text"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        assertNotNull(ast.getConcepts().get(0).getUi());
        assertEquals("Patient", ast.getConcepts().get(0).getUi().getLabel());
        assertEquals("First", ast.getConcepts().get(0).getFields().get(1).getUi().getShortLabel());

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "Expected no semantic errors, got: " + validation.getErrors());
        assertTrue(validation.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.getLayer() == ValidationLayer.UX_METADATA),
                "Expected no UX metadata warnings for a fully-labeled specimen.");

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals("Patient", compiled.findConcept("Patient").orElseThrow().getUi().getLabel());
        CompiledField firstName = compiled.findConcept("Patient")
                .orElseThrow()
                .getFields()
                .stream()
                .filter(field -> "firstName".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("First", firstName.getUi().getShortLabel());
        assertEquals("Marina", firstName.getUi().getExamples().get(0));
    }

    @Test
    void validatorWarnsOnMissingLabelsAndDuplicateOrder() throws Exception {
        Path modelPath = Files.createTempFile("npdev-presentation-metadata-error-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "presentation.metadata.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "firstName", "type": "string", "ui": { "order": 10 } },
                        { "name": "lastName", "type": "string", "ui": { "label": "Last name", "order": 10 } }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);

        assertTrue(validation.getErrors().isEmpty(), "Expected no hard semantic errors, got: " + validation.getErrors());
        assertFalse(validation.getWarnings().isEmpty(), "Expected UX metadata warnings.");
        List<ValidationDiagnostic> uxWarnings = validation.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getLayer() == ValidationLayer.UX_METADATA)
                .toList();
        assertEquals(3, uxWarnings.size(), "Expected concept label, field label, and duplicate-order warnings.");
        assertTrue(uxWarnings.stream().allMatch(diagnostic -> diagnostic.getSeverity() == ValidationSeverity.WARNING));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "missing_concept_label".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "missing_field_label".equals(diagnostic.getCode())));
        assertTrue(uxWarnings.stream().anyMatch(diagnostic -> "duplicate_field_order".equals(diagnostic.getCode())));
    }
}

