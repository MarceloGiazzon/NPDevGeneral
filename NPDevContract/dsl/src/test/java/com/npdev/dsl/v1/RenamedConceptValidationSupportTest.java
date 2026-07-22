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
 * LNCH-1 Phase 2 Task 2.3: {@code SemanticValidator} hygiene rules for the CONCEPT-level
 * {@code renamedFrom} marker (see plan §2.3), mirroring {@link RenamedFromValidationSupportTest}'s
 * field-level rules one for one, plus an explicit case proving that "still exists" also catches a
 * renamedFrom naming a completely unrelated OTHER concept's current name (not just the same
 * concept renamed onto itself), since concept names are model-wide, not scoped to one entity.
 */
class RenamedConceptValidationSupportTest {

    @Test
    void renamedFromEqualToOwnNameIsAWarning() throws Exception {
        Path modelPath = Files.createTempFile("npdev-concept-renamedfrom-self-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "renamed.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "renamedFrom": "Widget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);

        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("renamedFrom equals the concept's own name")),
                "Expected a self-rename warning, got warnings: " + result.getWarnings() + " errors: " + result.getErrors());
        assertTrue(result.getErrors().stream().noneMatch(e -> e.contains("renamedFrom")),
                "Self-rename must be a warning, not an error. Errors: " + result.getErrors());
    }

    @Test
    void renamedFromNamingAStillExistingConceptIsAnError() throws Exception {
        Path modelPath = Files.createTempFile("npdev-concept-renamedfrom-exists-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "renamed.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "renamedFrom": "Gadget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Gadget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(errors.stream().anyMatch(e -> e.contains("names a concept that still exists")),
                "Expected a still-exists ambiguity error, got: " + errors);
    }

    @Test
    void renamedFromNamingAnUnrelatedOtherConceptsCurrentNameIsAnError() throws Exception {
        // Distinct from the case above: here Widget's renamedFrom doesn't reference a concept it
        // could plausibly be replacing in a simple two-party rename -- it points at a THIRD,
        // wholly unrelated concept's current name. The "still exists" rule (entitiesByLower spans
        // every concept in the model) must catch this too, not just the two-party case.
        Path modelPath = Files.createTempFile("npdev-concept-renamedfrom-other-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "renamed.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "renamedFrom": "Sprocket",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Gadget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Sprocket",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(errors.stream().anyMatch(e -> e.contains("Widget") && e.contains("names a concept that still exists")),
                "Expected a still-exists ambiguity error naming Widget, got: " + errors);
    }

    @Test
    void twoConceptsDeclaringTheSameRenamedFromIsAnError() throws Exception {
        Path modelPath = Files.createTempFile("npdev-concept-renamedfrom-dup-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "renamed.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "WidgetNew",
                      "renamedFrom": "WidgetOld",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "GizmoNew",
                      "renamedFrom": "WidgetOld",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
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
        Path modelPath = Files.createTempFile("npdev-concept-renamedfrom-ok-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "renamed.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "renamedFrom": "OldWidget",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);

        assertTrue(result.getErrors().stream().noneMatch(e -> e.contains("renamedFrom")),
                "A legitimate concept rename must not produce errors. Errors: " + result.getErrors());
        assertTrue(result.getWarnings().stream().noneMatch(w -> w.contains("renamedFrom")),
                "A legitimate concept rename must not produce warnings. Warnings: " + result.getWarnings());
    }
}
