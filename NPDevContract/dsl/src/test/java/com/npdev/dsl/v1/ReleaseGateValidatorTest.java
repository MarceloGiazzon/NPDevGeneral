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
}
