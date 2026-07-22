package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 1 Task 1.1: {@code SemanticValidator} hygiene rules for the field-level
 * {@code renamedFrom} marker (see plan §2.1). One test per rule, plus a negative control proving a
 * legitimate rename produces zero errors/warnings.
 */
class RenamedFromValidationSupportTest {

    @Test
    void renamedFromEqualToOwnNameIsAWarning() throws Exception {
        Path modelPath = Files.createTempFile("npdev-renamedfrom-self-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "renamed.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "label", "type": "string", "renamedFrom": "label" }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);

        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("renamedFrom equals the field's own name")),
                "Expected a self-rename warning, got warnings: " + result.getWarnings() + " errors: " + result.getErrors());
        assertTrue(result.getErrors().stream().noneMatch(e -> e.contains("renamedFrom")),
                "Self-rename must be a warning, not an error. Errors: " + result.getErrors());
    }

    @Test
    void renamedFromNamingAStillExistingFieldIsAnError() throws Exception {
        Path modelPath = Files.createTempFile("npdev-renamedfrom-exists-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "renamed.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "label", "type": "string", "renamedFrom": "description" },
                        { "name": "description", "type": "string" }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(errors.stream().anyMatch(e -> e.contains("names a field that still exists in this concept")),
                "Expected a still-exists ambiguity error, got: " + errors);
    }

    @Test
    void twoFieldsDeclaringTheSameRenamedFromIsAnError() throws Exception {
        Path modelPath = Files.createTempFile("npdev-renamedfrom-dup-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "renamed.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "labelNew", "type": "string", "renamedFrom": "labelOld" },
                        { "name": "titleNew", "type": "string", "renamedFrom": "labelOld" }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(errors.stream().anyMatch(e -> e.contains("both declare renamedFrom")),
                "Expected a duplicate-renamedFrom ambiguity error, got: " + errors);
    }

    @Test
    void legitimateRenamedFromPointingAtANonExistentNameProducesNoErrorsOrWarnings() throws Exception {
        Path modelPath = Files.createTempFile("npdev-renamedfrom-ok-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "renamed.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "label", "type": "string", "renamedFrom": "oldLabel" }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);

        assertTrue(result.getErrors().stream().noneMatch(e -> e.contains("renamedFrom")),
                "A legitimate rename must not produce errors. Errors: " + result.getErrors());
        assertTrue(result.getWarnings().stream().noneMatch(w -> w.contains("renamedFrom")),
                "A legitimate rename must not produce warnings. Warnings: " + result.getWarnings());
    }
}
