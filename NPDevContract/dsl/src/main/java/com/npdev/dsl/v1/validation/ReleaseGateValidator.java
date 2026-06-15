package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.ReferenceSemanticsAst;
import com.npdev.dsl.v1.ast.TruthLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ReleaseGateValidator {

    @FunctionalInterface
    public interface EvidenceProvider {
        boolean hasEvidence(String conceptName, TruthLevel targetTruthLevel);

        static EvidenceProvider none() {
            return (conceptName, targetTruthLevel) -> false;
        }
    }

    public ValidationResult validatePromotion(
            ModelAst model,
            String conceptName,
            TruthLevel targetTruthLevel,
            EvidenceProvider evidenceProvider
    ) {
        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        if (model == null) {
            diagnostics.add(error("release_model_missing", "Model is required for release validation", null, null));
            return ValidationResult.fromDiagnostics(diagnostics);
        }
        TruthLevel target = targetTruthLevel == null ? TruthLevel.DEFAULT : targetTruthLevel;
        Map<String, ConceptAst> concepts = conceptsByName(model);
        ConceptAst root = concepts.get(normalize(conceptName));
        if (root == null) {
            diagnostics.add(error("release_concept_missing", "Concept not found for release validation: " + conceptName,
                    conceptName, null));
            return ValidationResult.fromDiagnostics(diagnostics);
        }

        for (ConceptAst reachable : reachableBondClosure(root, concepts)) {
            TruthLevel actual = reachable.getTruthLevel() == null ? TruthLevel.DEFAULT : reachable.getTruthLevel();
            if (actual.rank() < target.rank()) {
                diagnostics.add(error(
                        "truth_closure_below_target",
                        "Release gate blocks promotion of " + root.getName() + " to " + target.code()
                                + ": reachable bond dependency " + reachable.getName()
                                + " is only " + actual.code(),
                        reachable.getName(),
                        null
                ));
            }
        }

        if (target.rank() >= TruthLevel.T4_TESTED.rank()) {
            EvidenceProvider provider = evidenceProvider == null ? EvidenceProvider.none() : evidenceProvider;
            if (!provider.hasEvidence(root.getName(), target)) {
                diagnostics.add(error(
                        "truth_evidence_missing",
                        "Release gate requires evidence for " + root.getName() + " at " + target.code(),
                        root.getName(),
                        null
                ));
            }
        }

        return ValidationResult.fromDiagnostics(diagnostics);
    }

    public static EvidenceProvider evidencePaths(List<Path> paths) {
        List<Path> safePaths = paths == null ? List.of() : List.copyOf(paths);
        return (conceptName, targetTruthLevel) -> safePaths.stream().anyMatch(path ->
                path != null
                        && Files.exists(path)
                        && path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT)
                        .contains(normalize(conceptName))
        );
    }

    private static Map<String, ConceptAst> conceptsByName(ModelAst model) {
        Map<String, ConceptAst> out = new LinkedHashMap<>();
        for (ConceptAst concept : model.getConcepts()) {
            if (concept != null && concept.getName() != null) {
                out.put(normalize(concept.getName()), concept);
            }
        }
        return out;
    }

    private static List<ConceptAst> reachableBondClosure(ConceptAst root, Map<String, ConceptAst> concepts) {
        List<ConceptAst> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<ConceptAst> queue = new ArrayDeque<>();
        queue.add(root);
        seen.add(normalize(root.getName()));
        while (!queue.isEmpty()) {
            ConceptAst current = queue.removeFirst();
            out.add(current);
            for (FieldAst field : current.getFields()) {
                String targetName = referenceTarget(field);
                if (targetName == null || targetName.isBlank()) {
                    continue;
                }
                String key = normalize(targetName);
                if (!seen.add(key)) {
                    continue;
                }
                ConceptAst target = concepts.get(key);
                if (target != null) {
                    queue.add(target);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String referenceTarget(FieldAst field) {
        if (field == null) {
            return "";
        }
        ReferenceSemanticsAst semantics = field.getReferenceSemantics();
        if (semantics != null && semantics.getTarget() != null && !semantics.getTarget().isBlank()) {
            return semantics.getTarget();
        }
        return field.getReferenceTarget();
    }

    private static ValidationDiagnostic error(String code, String message, String concept, String field) {
        return new ValidationDiagnostic(
                ValidationLayer.RELEASE_GATE,
                ValidationSeverity.ERROR,
                code,
                message,
                "dsl",
                concept == null ? null : "$.concepts[" + concept + "]",
                concept,
                field,
                "release",
                code,
                "Raise dependency truth levels or lower the requested promotion target.",
                "validation.release." + code
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
