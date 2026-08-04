package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.TruthLevel;
import com.npdev.dsl.v1.parser.DeprecationException;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.ModelSchemaValidationException;
import com.npdev.dsl.v1.validation.ReleaseGateValidator;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import com.npdev.dsl.v1.validation.ValidationLayer;
import com.npdev.dsl.v1.validation.ValidationResult;
import com.npdev.dsl.v1.validation.ValidationSeverity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Standalone model validator entry point for the AI-authoring loop.
 *
 * <p>Runs the exact same structural (JSON Schema, via {@link JsonModelParser}) and semantic
 * (cross-reference, via {@link SemanticValidator}) validation the generator runs at
 * {@code GeneratorMain} lines 67-71, but WITHOUT generating anything -- so an authoring agent
 * can validate a draft {@code model.json} in one fast call before committing to a full build.
 *
 * <p>Emits a single machine-readable JSON report (see {@code validation-report.schema.json},
 * contract {@code npdev-validation-report.v2}) carrying every {@link ValidationDiagnostic}
 * field the pipeline already produces -- {@code layer}, {@code severity}, {@code code},
 * {@code path}, {@code concept}, {@code field}, {@code suggestedFix} -- so the caller can loop
 * on structured diagnostics instead of scraping a truncated error string.
 *
 * <p>Exit code: {@code 0} when the model passes (or has warnings only), {@code 2} when it has
 * errors, {@code 64} on usage error. The report is always printed to stdout and, when
 * {@code --out} is supplied, also written to that file (the file is the noise-free channel the
 * CLI/MCP layer reads).
 */
public final class ModelValidatorMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_VERSION = "npdev-validation-report.v2";

    private ModelValidatorMain() {
    }

    public static void main(String[] args) {
        // Diagnostics feed an English-language authoring loop -- pin the locale so the underlying
        // schema validator emits English messages regardless of the host machine's locale.
        Locale.setDefault(Locale.ENGLISH);

        String modelArg = null;
        String outArg = null;
        boolean releaseGate = false;
        String targetTruthLevelArg = null;
        List<String> evidencePathArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--out".equals(arg) && i + 1 < args.length) {
                outArg = args[++i];
            } else if (arg.startsWith("--out=")) {
                outArg = arg.substring("--out=".length());
            } else if ("--releaseGate".equals(arg)) {
                releaseGate = true;
            } else if (arg.startsWith("--targetTruthLevel=")) {
                targetTruthLevelArg = arg.substring("--targetTruthLevel=".length());
            } else if (arg.startsWith("--evidencePath=")) {
                evidencePathArgs.add(arg.substring("--evidencePath=".length()));
            } else if (!arg.startsWith("--")) {
                modelArg = arg;
            }
        }
        if (modelArg == null) {
            System.err.println("usage: ModelValidatorMain <model.json> [--out report.json] "
                    + "[--releaseGate --targetTruthLevel=<T0..T6> [--evidencePath=<path>]...]");
            System.exit(64);
            return;
        }

        Path modelPath = Path.of(modelArg);
        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        ModelAst ast = parseCollectingDiagnostics(modelPath, diagnostics);

        // Only run semantic validation when the model parsed -- a structurally broken model has
        // no AST to cross-check, and reporting the parse error alone is the actionable signal.
        if (ast != null) {
            try {
                ValidationResult result = new SemanticValidator().validateWithWarnings(ast);
                diagnostics.addAll(result.getDiagnostics());
            } catch (RuntimeException semanticError) {
                diagnostics.add(structural(
                        "Semantic validation crashed: " + safeMessage(semanticError)));
            }

            // R81 (ledger/items/REG-81.yml): ReleaseGateValidator.validatePromotion is fully built
            // and unit-tested but, before this, called by nothing except its own test -- truth-level
            // promotion gating was dormant. Opt-in (both flags required together) so every existing
            // caller of this CLI sees byte-identical behavior when it doesn't ask for this.
            if (releaseGate) {
                diagnostics.addAll(runReleaseGate(ast, targetTruthLevelArg, evidencePathArgs));
            }
        }

        ObjectNode report = buildReport(modelPath.toString(), denoiseOneOfCascade(diagnostics));
        String json = serialize(report);

        if (outArg != null) {
            writeReport(outArg, json);
        }
        System.out.println(json);
        System.exit("failed".equals(report.get("status").asText()) ? 2 : 0);
    }

    private static ModelAst parseCollectingDiagnostics(Path modelPath, List<ValidationDiagnostic> diagnostics) {
        try {
            return new JsonModelParser().parse(modelPath);
        } catch (ModelSchemaValidationException schemaError) {
            diagnostics.addAll(schemaError.getDiagnostics());
        } catch (DeprecationException deprecation) {
            if (deprecation.getDiagnostic() != null) {
                diagnostics.add(deprecation.getDiagnostic());
            } else {
                diagnostics.add(structural(safeMessage(deprecation)));
            }
        } catch (IOException | RuntimeException parseError) {
            diagnostics.add(structural(safeMessage(parseError)));
        }
        return null;
    }

    /**
     * R81: promotes every concept in the model to {@code targetTruthLevelArg} via {@link
     * ReleaseGateValidator#validatePromotion}, mirroring {@code ReleaseGateValidatorTest.java:56}'s
     * argument shape. {@code --evidencePath} (repeatable, optional) feeds {@link
     * ReleaseGateValidator#evidencePaths}; with none supplied, T4+ promotion always reports missing
     * evidence (the class's own documented behavior for {@link
     * ReleaseGateValidator.EvidenceProvider#none()}), not a bug in this wiring.
     */
    static List<ValidationDiagnostic> runReleaseGate(
            ModelAst ast, String targetTruthLevelArg, List<String> evidencePathArgs) {
        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        TruthLevel target = TruthLevel.fromString(targetTruthLevelArg);
        if (target == null) {
            diagnostics.add(structural(
                    "--releaseGate requires --targetTruthLevel=<T0..T6>; got: "
                            + (targetTruthLevelArg == null ? "(none)" : targetTruthLevelArg)));
            return diagnostics;
        }
        List<Path> evidencePaths = new ArrayList<>();
        for (String evidencePathArg : evidencePathArgs) {
            evidencePaths.add(Path.of(evidencePathArg));
        }
        ReleaseGateValidator.EvidenceProvider evidenceProvider = evidencePaths.isEmpty()
                ? ReleaseGateValidator.EvidenceProvider.none()
                : ReleaseGateValidator.evidencePaths(evidencePaths);
        ReleaseGateValidator releaseGateValidator = new ReleaseGateValidator();
        for (ConceptAst concept : ast.getConcepts()) {
            if (concept == null || concept.getName() == null || concept.getName().isBlank()) {
                continue;
            }
            ValidationResult result = releaseGateValidator.validatePromotion(
                    ast, concept.getName(), target, evidenceProvider);
            diagnostics.addAll(result.getDiagnostics());
        }
        return diagnostics;
    }

    /**
     * Collapse the {@code oneOf} error cascade the JSON-Schema layer produces.
     *
     * <p>The model schema allows a concept node to be a full concept OR a {@code {"$ref": ...}}
     * pack import ({@code oneOf}). When an authored concept has a real error (e.g. an invented
     * field key), the failing full-concept branch reports the real, deep error, but the losing
     * {@code $ref} branch also dumps its complaints at the concept level ({@code required '$ref'},
     * {@code 'fields' not defined}, ...). Those parent-level messages are pure noise that
     * misdirects an authoring agent.
     *
     * <p>Rule: for each structural {@code oneOf} marker at path P, if a structural diagnostic
     * exists strictly DEEPER than P (the branch the author actually intended, carrying the real
     * cause), drop every structural diagnostic anchored exactly at P. If no deeper signal exists,
     * nothing is dropped -- we never hide the only error. Any rare over-collapse resurfaces on the
     * next validation pass once the deeper error is fixed. Semantic/UX diagnostics are untouched.
     */
    static List<ValidationDiagnostic> denoiseOneOfCascade(List<ValidationDiagnostic> diagnostics) {
        Set<String> oneOfPaths = new LinkedHashSet<>();
        for (ValidationDiagnostic diagnostic : diagnostics) {
            if (isStructural(diagnostic) && diagnostic.getPath() != null && isOneOfMarker(diagnostic)) {
                oneOfPaths.add(diagnostic.getPath());
            }
        }
        if (oneOfPaths.isEmpty()) {
            return diagnostics;
        }

        Set<String> collapsePaths = new LinkedHashSet<>();
        for (String oneOfPath : oneOfPaths) {
            for (ValidationDiagnostic diagnostic : diagnostics) {
                if (isStructural(diagnostic) && isStrictlyDeeper(diagnostic.getPath(), oneOfPath)) {
                    collapsePaths.add(oneOfPath);
                    break;
                }
            }
        }
        if (collapsePaths.isEmpty()) {
            return diagnostics;
        }

        List<ValidationDiagnostic> kept = new ArrayList<>();
        for (ValidationDiagnostic diagnostic : diagnostics) {
            if (isStructural(diagnostic)
                    && diagnostic.getPath() != null
                    && collapsePaths.contains(diagnostic.getPath())) {
                continue;  // losing-branch artifact at the oneOf anchor
            }
            kept.add(diagnostic);
        }
        return kept;
    }

    private static boolean isStructural(ValidationDiagnostic diagnostic) {
        return diagnostic.getLayer() == ValidationLayer.STRUCTURAL;
    }

    private static boolean isOneOfMarker(ValidationDiagnostic diagnostic) {
        String code = diagnostic.getCode();
        if (code != null && code.toLowerCase(Locale.ROOT).contains("oneof")) {
            return true;
        }
        String message = diagnostic.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("one and only one");
    }

    private static boolean isStrictlyDeeper(String child, String parent) {
        if (child == null || parent == null || child.length() <= parent.length()) {
            return false;
        }
        if (!child.startsWith(parent)) {
            return false;
        }
        char boundary = child.charAt(parent.length());
        return boundary == '.' || boundary == '[';
    }

    private static ValidationDiagnostic structural(String message) {
        return new ValidationDiagnostic(
                ValidationLayer.STRUCTURAL,
                ValidationSeverity.ERROR,
                "STRUCTURAL_ERROR",
                message == null || message.isBlank() ? "structural validation failed" : message,
                "NPDevContract",
                null
        );
    }

    private static ObjectNode buildReport(String modelPath, List<ValidationDiagnostic> diagnostics) {
        int errors = 0;
        int warnings = 0;
        ArrayNode diagArray = MAPPER.createArrayNode();
        for (ValidationDiagnostic diagnostic : diagnostics) {
            if (diagnostic.getSeverity() == ValidationSeverity.ERROR) {
                errors++;
            } else if (diagnostic.getSeverity() == ValidationSeverity.WARNING) {
                warnings++;
            }
            diagArray.add(diagnosticNode(diagnostic));
        }
        String status = errors > 0 ? "failed" : (warnings > 0 ? "warning" : "passed");

        ObjectNode report = MAPPER.createObjectNode();
        report.put("contractVersion", CONTRACT_VERSION);
        report.put("status", status);
        report.put("model", modelPath);
        ObjectNode summary = report.putObject("summary");
        summary.put("errors", errors);
        summary.put("warnings", warnings);
        report.set("diagnostics", diagArray);
        return report;
    }

    private static ObjectNode diagnosticNode(ValidationDiagnostic diagnostic) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("layer", diagnostic.getLayer().getExternalName());
        node.put("severity", diagnostic.getSeverity().getExternalName());
        putIfPresent(node, "code", diagnostic.getCode());
        node.put("message", diagnostic.getMessage());
        putIfPresent(node, "path", diagnostic.getPath());
        putIfPresent(node, "concept", diagnostic.getConcept());
        putIfPresent(node, "field", diagnostic.getField());
        putIfPresent(node, "section", diagnostic.getSection());
        putIfPresent(node, "ruleName", diagnostic.getRuleName());
        putIfPresent(node, "suggestedFix", diagnostic.getSuggestedFix());
        putIfPresent(node, "helpKey", diagnostic.getHelpKey());
        putIfPresent(node, "boundaryId", diagnostic.getBoundaryId());
        return node;
    }

    private static void putIfPresent(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.put(key, value);
        }
    }

    private static String serialize(ObjectNode report) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (IOException serializationError) {
            // A report we cannot serialize is a programming error, not a model error -- fail loud.
            throw new IllegalStateException("failed to serialize validation report", serializationError);
        }
    }

    private static void writeReport(String outArg, String json) {
        try {
            Path outPath = Path.of(outArg);
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            Files.writeString(outPath, json + System.lineSeparator());
        } catch (IOException writeError) {
            System.err.println("failed to write report to " + outArg + ": " + safeMessage(writeError));
            System.exit(70);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
