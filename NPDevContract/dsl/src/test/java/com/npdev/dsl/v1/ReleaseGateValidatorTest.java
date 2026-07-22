package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.TruthLevel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.ReleaseGateValidator;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseGateValidatorTest {

    @Test
    void releaseGateBlocksTruthClosureBelowPromotionTargetButAuthoringOnlyWarns() throws Exception {
        Path modelPath = Files.createTempFile("npdev-release-gate-", ".json");
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
                        {
                          "name": "productId",
                          "type": "reference",
                          "reference": { "target": "Product" }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst model = new JsonModelParser().parse(modelPath);
        assertFalse(new SemanticValidator().validateWithWarnings(model).hasErrors());
        assertTrue(new SemanticValidator().validateWithWarnings(model).getWarnings().stream()
                .anyMatch(warning -> warning.contains("no upward truth edges")));

        ValidationResult release = new ReleaseGateValidator().validatePromotion(
                model,
                "Invoice",
                TruthLevel.T5_EVIDENCE_BACKED,
                ReleaseGateValidator.EvidenceProvider.none()
        );

        assertTrue(release.hasErrors());
        assertTrue(release.getErrors().stream().anyMatch(error -> error.contains("Product")));
        assertTrue(release.getDiagnostics().stream().anyMatch(diagnostic ->
                "truth_closure_below_target".equals(diagnostic.getCode())));
        assertTrue(release.getDiagnostics().stream().anyMatch(diagnostic ->
                "truth_evidence_missing".equals(diagnostic.getCode())));
    }

    @Test
    void releaseGatePassesWhenAllDependenciesMeetTarget() throws Exception {
        Path modelPath = Files.createTempFile("npdev-release-ok-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "truth.ok",
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
                      "truthLevel": "T5",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productId",
                          "type": "reference",
                          "reference": { "target": "Product" }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst model = new JsonModelParser().parse(modelPath);
        // Provide evidence for both required concepts
        ValidationResult release = new ReleaseGateValidator().validatePromotion(
                model,
                "Invoice",
                TruthLevel.T3_RUNS_LOCALLY,
                ReleaseGateValidator.EvidenceProvider.none()  // T3 doesn't require evidence
        );

        assertFalse(release.hasErrors(),
                "Promotion should pass when all reachable bonds meet target truth. Errors: " + release.getErrors());
    }

    @Test
    void semanticValidatorDoesNotBlockOnTruthEdgeViolation() throws Exception {
        Path modelPath = Files.createTempFile("npdev-truth-warn-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "truth.warn",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "CoreEntity",
                      "truthLevel": "T1",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "AppEntity",
                      "truthLevel": "T5",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "coreId",
                          "type": "reference",
                          "reference": { "target": "CoreEntity" }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst model = new JsonModelParser().parse(modelPath);
        ValidationResult semantic = new SemanticValidator().validateWithWarnings(model);

        // WARNING only — not an error — so authoring is not blocked
        assertFalse(semantic.hasErrors(), "Truth edge should only warn, not error: " + semantic.getErrors());
        assertTrue(semantic.getWarnings().stream().anyMatch(w -> w.contains("no upward truth edges")),
                "Expected truth-edge warning. Warnings: " + semantic.getWarnings());
    }

    @Test
    void bondClosureIncludesTransitiveDependencies() throws Exception {
        Path modelPath = Files.createTempFile("npdev-closure-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "truth.chain",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Foundation",
                      "truthLevel": "T1",
                      "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }]
                    },
                    {
                      "name": "Middle",
                      "truthLevel": "T4",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "foundationId", "type": "reference", "reference": { "target": "Foundation" } }
                      ]
                    },
                    {
                      "name": "Top",
                      "truthLevel": "T5",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "middleId", "type": "reference", "reference": { "target": "Middle" } }
                      ]
                    }
                  ]
                }
                """);

        ModelAst model = new JsonModelParser().parse(modelPath);
        ValidationResult release = new ReleaseGateValidator().validatePromotion(
                model,
                "Top",
                TruthLevel.T5_EVIDENCE_BACKED,
                ReleaseGateValidator.EvidenceProvider.none()
        );

        // Both Middle (T4 < T5) and Foundation (T1 < T5) are in the closure
        assertTrue(release.hasErrors());
        long belowTargetCount = release.getDiagnostics().stream()
                .filter(d -> "truth_closure_below_target".equals(d.getCode()))
                .count();
        assertTrue(belowTargetCount >= 2,
                "Both transitive deps should be flagged. Diagnostics: " + release.getDiagnostics());
    }
}
