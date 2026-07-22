package com.npdev.dsl.v1;

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

class DefaultsAndDerivedFieldsSupportTest {

    @Test
    void parserValidatorAndCompilerSupportDefaultsAndDerivedFields() throws Exception {
        Path modelPath = Files.createTempFile("npdev-defaults-derived-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "value.behavior.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "firstName", "type": "string", "required": true },
                        { "name": "lastName", "type": "string", "required": true },
                        { "name": "preferredLanguage", "type": "string", "default": "en-US" },
                        { "name": "reminderLanguage", "type": "string", "defaultExpression": "preferredLanguage" },
                        { "name": "chartLabel", "type": "string", "derivedExpression": "concat(lastName, ', ', firstName)" }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected semantic validation to accept defaults/derived fields, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledField preferredLanguage = findField(compiled, "Patient", "preferredLanguage");
        CompiledField reminderLanguage = findField(compiled, "Patient", "reminderLanguage");
        CompiledField chartLabel = findField(compiled, "Patient", "chartLabel");

        assertNotNull(preferredLanguage.getSchema());
        assertEquals("en-US", preferredLanguage.getSchema().getDefaultValue());
        assertEquals("preferredLanguage", reminderLanguage.getSchema().getDefaultExpression());
        assertEquals("concat(lastName, ', ', firstName)", chartLabel.getSchema().getDerivedExpression());
    }

    @Test
    void semanticValidationRejectsUnknownSelfReferentialAndCyclicValueBehaviors() throws Exception {
        Path modelPath = Files.createTempFile("npdev-defaults-derived-error-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "value.behavior.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "firstName", "type": "string" },
                        { "name": "badDefault", "type": "string", "defaultExpression": "missingField" },
                        { "name": "selfDerived", "type": "string", "derivedExpression": "selfDerived" },
                        { "name": "cycleA", "type": "string", "defaultExpression": "cycleB" },
                        { "name": "cycleB", "type": "string", "derivedExpression": "cycleA" }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertFalse(errors.isEmpty(), "Expected semantic validation errors for invalid value behaviors.");
        assertTrue(errors.stream().anyMatch(error -> error.contains("references unknown field missingField")),
                "Expected unknown field error, got: " + errors);
        assertTrue(errors.stream().anyMatch(error -> error.contains("cannot reference itself")),
                "Expected self-reference error, got: " + errors);
        assertTrue(errors.stream().anyMatch(error -> error.contains("dependency cycle detected")),
                "Expected cycle-detection error, got: " + errors);
    }

    private static CompiledField findField(CompiledModel model, String entityName, String fieldName) {
        return model.findConcept(entityName)
                .orElseThrow()
                .getFields()
                .stream()
                .filter(field -> fieldName.equals(field.getName()))
                .findFirst()
                .orElseThrow();
    }
}

