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

class ReferenceSemanticsSupportTest {

    @Test
    void parserValidatorAndCompilerSupportReferenceSemantics() throws Exception {
        Path modelPath = Files.createTempFile("npdev-reference-semantics-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "reference.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "mrn", "type": "string", "unique": true },
                        { "name": "firstName", "type": "string" },
                        { "name": "lastName", "type": "string" }
                      ]
                    },
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "patientId",
                          "type": "reference",
                          "required": true,
                          "reference": {
                            "target": "Patient",
                            "displayField": "lastName",
                            "displayTemplate": "{{lastName}}, {{firstName}} ({{mrn}})",
                            "searchFields": ["mrn", "lastName", "firstName"],
                            "pickerColumns": ["mrn", "lastName", "firstName"],
                            "previewFields": ["mrn", "firstName", "lastName"],
                            "previewCardTemplate": "{{lastName}}, {{firstName}} | MRN {{mrn}}",
                            "defaultFilter": "recent-patients",
                            "inlineCreate": "allow"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        FieldAst patientReference = ast.getEntities().get(1).getFields().get(1);
        assertEquals("Patient", patientReference.getReferenceTarget());
        assertNotNull(patientReference.getReferenceSemantics());
        assertEquals("lastName", patientReference.getReferenceSemantics().getDisplayField());
        assertEquals(List.of("mrn", "lastName", "firstName"), patientReference.getReferenceSemantics().getSearchFields());
        assertEquals(List.of("mrn", "lastName", "firstName"), patientReference.getReferenceSemantics().getPickerColumns());
        assertEquals("{{lastName}}, {{firstName}} ({{mrn}})", patientReference.getReferenceSemantics().getDisplayTemplate());

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected semantic validation to accept reference semantics, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledField compiledReference = compiled.findEntity("Appointment")
                .orElseThrow()
                .getFields()
                .stream()
                .filter(field -> "patientId".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("Patient", compiledReference.getReferenceTarget());
        assertNotNull(compiledReference.getReferenceSemantics());
        assertEquals("lastName", compiledReference.getReferenceSemantics().getDisplayField());
        assertEquals(List.of("mrn", "lastName", "firstName"), compiledReference.getReferenceSemantics().getSearchFields());
        assertEquals("allow", compiledReference.getReferenceSemantics().getInlineCreatePolicy());
        assertEquals("recent-patients", compiledReference.getReferenceSemantics().getDefaultFilter());
        assertEquals("{{lastName}}, {{firstName}} ({{mrn}})", compiledReference.getReferenceSemantics().getDisplayTemplate());
        assertEquals("{{lastName}}, {{firstName}} | MRN {{mrn}}", compiledReference.getReferenceSemantics().getPreviewCardTemplate());
    }

    @Test
    void semanticValidationRejectsReferenceHintsThatMissTargetFields() throws Exception {
        Path modelPath = Files.createTempFile("npdev-reference-semantics-error-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "reference.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "lastName", "type": "string" }
                      ]
                    },
                    {
                      "name": "Appointment",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "patientId",
                          "type": "reference",
                          "reference": {
                            "target": "Patient",
                            "displayField": "missingField",
                            "searchFields": ["lastName", "missingField"],
                            "previewFields": ["lastName"],
                            "pickerColumns": ["lastName", "missingColumn"],
                            "displayTemplate": "{{lastName}} {{missingField}}",
                            "previewCardTemplate": "{{missingField}}",
                            "inlineCreate": "allow"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertFalse(errors.isEmpty(), "Expected semantic validation errors for invalid reference hints.");
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("reference displayField not found")),
                "Expected invalid reference displayField error, got: " + errors
        );
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("reference pickerColumns not found")),
                "Expected invalid pickerColumns error, got: " + errors
        );
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("reference displayTemplate references unknown target field")),
                "Expected invalid displayTemplate reference error, got: " + errors
        );
    }
}

