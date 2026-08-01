package com.npdev.dsl.v1.cli;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R81 (ledger/items/REG-81.yml): {@code ModelValidatorMain}'s opt-in release-gate wiring --
 * {@code ReleaseGateValidator.validatePromotion} was fully built and unit-tested but called by
 * nothing except its own test before this. These tests exercise the extracted {@code
 * runReleaseGate} helper directly (the same shape {@code main()} calls it with) rather than
 * {@code main()} itself, which calls {@code System.exit} and is not test-friendly.
 */
class ModelValidatorMainReleaseGateTest {

    private static ModelAst parse(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-release-gate-cli-", ".json");
        Files.writeString(modelPath, json);
        return new JsonModelParser().parse(modelPath);
    }

    private static final String MODEL_ALL_T3 = """
            {
              "namespace": "release.cli.ok", "dslVersion": "1.0.0", "version": "1.0",
              "concepts": [
                { "name": "Product", "truthLevel": "T3",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Invoice", "truthLevel": "T3",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "productId", "type": "reference", "reference": { "target": "Product" } }
                  ] }
              ]
            }
            """;

    private static final String MODEL_MIXED_LEVELS = """
            {
              "namespace": "release.cli.mixed", "dslVersion": "1.0.0", "version": "1.0",
              "concepts": [
                { "name": "Product", "truthLevel": "T1",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Invoice", "truthLevel": "T5",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "productId", "type": "reference", "reference": { "target": "Product" } }
                  ] }
              ]
            }
            """;

    @Test
    void allConceptsAtOrAboveTargetProducesNoDiagnostics() throws Exception {
        ModelAst model = parse(MODEL_ALL_T3);
        List<ValidationDiagnostic> diagnostics = ModelValidatorMain.runReleaseGate(model, "T3", List.of());
        assertTrue(diagnostics.isEmpty(), "expected no release-gate diagnostics, got: " + diagnostics);
    }

    @Test
    void conceptBelowTargetInBondClosureIsReported() throws Exception {
        ModelAst model = parse(MODEL_MIXED_LEVELS);
        List<ValidationDiagnostic> diagnostics = ModelValidatorMain.runReleaseGate(model, "T3", List.of());
        assertTrue(diagnostics.stream().anyMatch(d -> "truth_closure_below_target".equals(d.getCode())),
                "expected a truth_closure_below_target diagnostic, got: " + diagnostics);
    }

    @Test
    void t4PlusWithNoEvidencePathReportsMissingEvidence() throws Exception {
        ModelAst model = parse(MODEL_ALL_T3);
        List<ValidationDiagnostic> diagnostics = ModelValidatorMain.runReleaseGate(model, "T5", List.of());
        assertTrue(diagnostics.stream().anyMatch(d -> "truth_evidence_missing".equals(d.getCode())),
                "expected truth_evidence_missing at T5 with no evidence paths, got: " + diagnostics);
    }

    @Test
    void t4PlusWithMatchingEvidencePathSuppressesMissingEvidence() throws Exception {
        ModelAst model = parse(MODEL_ALL_T3);
        Path evidenceDir = Files.createTempDirectory("npdev-release-gate-evidence-");
        Path productEvidence = evidenceDir.resolve("product-proof.txt");
        Files.writeString(productEvidence, "proof");
        Path invoiceEvidence = evidenceDir.resolve("invoice-proof.txt");
        Files.writeString(invoiceEvidence, "proof");

        List<ValidationDiagnostic> diagnostics = ModelValidatorMain.runReleaseGate(
                model, "T5", List.of(productEvidence.toString(), invoiceEvidence.toString()));
        assertFalse(diagnostics.stream().anyMatch(d -> "truth_evidence_missing".equals(d.getCode())),
                "expected no truth_evidence_missing with matching evidence paths, got: " + diagnostics);
    }

    @Test
    void missingTargetTruthLevelReportsAUsageDiagnostic() throws Exception {
        ModelAst model = parse(MODEL_ALL_T3);
        List<ValidationDiagnostic> diagnostics = ModelValidatorMain.runReleaseGate(model, null, List.of());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).getMessage().contains("--targetTruthLevel"),
                "expected a --targetTruthLevel usage diagnostic, got: " + diagnostics);
    }

    @Test
    void invalidTargetTruthLevelReportsAUsageDiagnostic() throws Exception {
        ModelAst model = parse(MODEL_ALL_T3);
        List<ValidationDiagnostic> diagnostics = ModelValidatorMain.runReleaseGate(model, "T9", List.of());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).getMessage().contains("T9"),
                "expected the invalid value echoed back, got: " + diagnostics);
    }
}
