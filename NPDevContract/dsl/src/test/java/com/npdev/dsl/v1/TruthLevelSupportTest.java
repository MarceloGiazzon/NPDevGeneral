package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.TruthLevel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concept truth levels (T0–T6) and the bond truth-edge invariant
 * ("no upward edges" — a concept may not depend on a less-true concept).
 */
class TruthLevelSupportTest {

    @Test
    void parserReadsConceptTruthLevelAndDefaultsToDeclared() throws Exception {
        Path modelPath = Files.createTempFile("npdev-truth-parse-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "truth.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Customer",
                      "truthLevel": "T5",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Note",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        assertEquals(TruthLevel.T5_EVIDENCE_BACKED, ast.getConcepts().get(0).getTruthLevel());
        assertEquals(TruthLevel.DEFAULT, ast.getConcepts().get(1).getTruthLevel());
        assertEquals(TruthLevel.T1_DECLARED, ast.getConcepts().get(1).getTruthLevel());
    }

    @Test
    void upwardTruthEdgeWarnsButDoesNotBlock() throws Exception {
        Path modelPath = Files.createTempFile("npdev-truth-edge-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "truth.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "truthLevel": "T1",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "truthLevel": "T5",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "productId", "type": "reference", "reference": { "target": "Product" } }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        SemanticValidator validator = new SemanticValidator();

        assertTrue(validator.validate(ast).isEmpty(), "Truth edges must not block creation (no errors).");
        List<String> warnings = validator.validateWithWarnings(ast).getWarnings();
        assertTrue(
                warnings.stream().anyMatch(w -> w.contains("no upward truth edges")),
                "Expected an upward-truth-edge warning, got: " + warnings
        );
    }

    @Test
    void downwardTruthEdgeProducesNoTruthWarning() throws Exception {
        Path modelPath = Files.createTempFile("npdev-truth-ok-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "truth.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "truthLevel": "T5",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "truthLevel": "T1",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "productId", "type": "reference", "reference": { "target": "Product" } }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> warnings = new SemanticValidator().validateWithWarnings(ast).getWarnings();
        assertFalse(
                warnings.stream().anyMatch(w -> w.contains("no upward truth edges")),
                "A bond to an equal-or-higher truth concept must not warn, got: " + warnings
        );
    }
}
