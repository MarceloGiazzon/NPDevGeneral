package com.npdev.generator.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * REG-102's sibling fix, Wave 2 (E2, {@code AI_AUTHORING_CONTRACT-2026-07-31.md} Part 9): the
 * Custodian's diff gate as a real, callable command --
 * {@code npdev author diff-gate --previous <m> --submitted <m> --manifest <j>}.
 *
 * <p>Deliberately its own entry point (same reasoning as {@code ModelChangeClassifierMain}):
 * never a {@code GeneratorMain} flag. {@code --manifest} is technically optional at this CLI
 * layer -- omitting it does not skip the check, it makes {@link AuthoringDiffGate#evaluate} itself
 * produce the C1 refusal ("no manifest") as a normal diagnostic, so the failure path is uniform
 * rather than a separate usage error.
 *
 * <p>Report contract {@code npdev-authoring-diff-gate-report.v1}, deliberately matching
 * {@code ModelValidatorMain}'s {@code npdev-validation-report.v2} shape (C7: "must match that
 * shape or the loop cannot close") -- {@code status}/{@code diagnostics[]} with {@code code},
 * {@code severity}, {@code path}, {@code suggestedFix} on every entry. Exit {@code 0} on pass,
 * {@code 2} on any ERROR-severity violation, {@code 64} on usage error.
 *
 * <p>{@code --archiveDir <dir>} (E5, "almost free" per the contract's own sizing): on a PASS
 * only, writes the previous model's exact bytes to
 * {@code <dir>/<previousModelVersion>-<sha256hex>.json} -- "a contract about continuity that
 * cannot produce the prior document is unenforceable at the next iteration" (C6). Never archives
 * on a failed gate -- there is nothing accepted yet to preserve a predecessor of.
 *
 * <p><b>S5 merge mode</b> ({@code __OutsideRepo\s5\S5_SPEC.md} I5: "surface it where the diff gate
 * already lives -- do not build a second entry point"): passing {@code --theirs <model.json>}
 * treats {@code --previous} as the shared BASE, {@code --submitted}/{@code --manifest} as OURS
 * (already landed), and {@code --theirs}/{@code --theirsManifest} as the incoming THEIRS submission,
 * and runs {@link AuthoringMergeGate} instead of refusing outright on a stale base. The report gains
 * a {@code merge} block; {@code --mergedOut} writes the merged {@code model.json} on success,
 * {@code --mergedManifestOut} writes a synthesized manifest recording {@code mergeOutcome}
 * (provenance: which side contributed which element) for later inspection.
 */
public final class AuthoringDiffGateMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_VERSION = "npdev-authoring-diff-gate-report.v1";

    private AuthoringDiffGateMain() {
    }

    public static void main(String[] args) throws IOException {
        String previousPath = null;
        String submittedPath = null;
        String manifestPath = null;
        String outPath = null;
        String archiveDir = null;
        String theirsPath = null;
        String theirsManifestPath = null;
        String mergedOutPath = null;
        String mergedManifestOutPath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--previous" -> previousPath = args[++i];
                case "--submitted" -> submittedPath = args[++i];
                case "--manifest" -> manifestPath = args[++i];
                case "--out" -> outPath = args[++i];
                case "--archiveDir" -> archiveDir = args[++i];
                case "--theirs" -> theirsPath = args[++i];
                case "--theirsManifest" -> theirsManifestPath = args[++i];
                case "--mergedOut" -> mergedOutPath = args[++i];
                case "--mergedManifestOut" -> mergedManifestOutPath = args[++i];
                default -> {
                    System.err.println("Unrecognized argument: " + args[i]
                            + " (supported: --previous, --submitted, --manifest, --out, --archiveDir, "
                            + "--theirs, --theirsManifest, --mergedOut, --mergedManifestOut)");
                    System.exit(64);
                    return;
                }
            }
        }
        if (previousPath == null || submittedPath == null) {
            System.err.println("usage: AuthoringDiffGateMain --previous <model.json> --submitted <model.json> "
                    + "[--manifest <manifest.json>] [--out <report.json>] "
                    + "[--theirs <model.json> --theirsManifest <manifest.json> "
                    + "[--mergedOut <model.json>] [--mergedManifestOut <manifest.json>]]");
            System.exit(64);
            return;
        }

        Path previous = Path.of(previousPath);
        Path submitted = Path.of(submittedPath);
        byte[] previousBytes = Files.readAllBytes(previous);
        String previousSha256Hex = sha256Hex(previousBytes);

        ModelAst previousModel = new JsonModelParser().parse(previous);
        ModelAst submittedModel = new JsonModelParser().parse(submitted);
        JsonNode manifest = manifestPath == null ? null : MAPPER.readTree(Path.of(manifestPath).toFile());

        AuthoringDiffGate.GateResult result = AuthoringDiffGate.evaluate(previousSha256Hex, previousModel, submittedModel, manifest);

        String archivedTo = null;
        if (result.passed() && archiveDir != null) {
            archivedTo = archivePrevious(previousBytes, previousModel.getVersion(), previousSha256Hex, archiveDir);
        }

        AuthoringMergeGate.MergeResult mergeResult = null;
        if (theirsPath != null) {
            Path theirs = Path.of(theirsPath);
            byte[] submittedBytes = Files.readAllBytes(submitted);
            byte[] theirsBytes = Files.readAllBytes(theirs);
            JsonNode previousJson = MAPPER.readTree(previous.toFile());
            JsonNode submittedJson = MAPPER.readTree(submitted.toFile());
            JsonNode theirsJson = MAPPER.readTree(theirs.toFile());
            ModelAst theirsModel = new JsonModelParser().parse(theirs);
            JsonNode theirsManifest = theirsManifestPath == null
                    ? null : MAPPER.readTree(Path.of(theirsManifestPath).toFile());

            mergeResult = AuthoringMergeGate.merge(
                    previousJson, previousModel, submittedJson, submittedModel,
                    theirsJson, theirsModel, manifest, theirsManifest);

            if (mergeResult.merged()) {
                if (mergedOutPath != null) {
                    writeFile(Path.of(mergedOutPath), AuthoringMergeGate.toJson(mergeResult.mergedModel()));
                }
                if (mergedManifestOutPath != null) {
                    ObjectNode mergedManifest = buildMergedManifest(
                            previousSha256Hex, previousModel.getVersion(), sha256Hex(submittedBytes),
                            sha256Hex(theirsBytes), mergeResult, manifest, theirsManifest);
                    writeFile(Path.of(mergedManifestOutPath),
                            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(mergedManifest) + "\n");
                }
            }
        }

        ObjectNode report = buildReport(previousSha256Hex, result, archivedTo, mergeResult);
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        if (outPath != null) {
            Path out = Path.of(outPath).toAbsolutePath().normalize();
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, json, StandardCharsets.UTF_8);
        }
        System.out.println(json);
        boolean passed = result.passed() && (mergeResult == null || mergeResult.merged());
        System.exit(passed ? 0 : 2);
    }

    private static void writeFile(Path path, String content) throws IOException {
        Path resolved = path.toAbsolutePath().normalize();
        if (resolved.getParent() != null) {
            Files.createDirectories(resolved.getParent());
        }
        Files.writeString(resolved, content, StandardCharsets.UTF_8);
    }

    /** E5: {@code <dir>/<previousVersion>-<sha256hex>.json}, the previous model's bytes verbatim
     *  (never re-serialized -- the archive must be byte-identical to what the Author was actually
     *  handed as I1, not a re-derived approximation). */
    private static String archivePrevious(byte[] previousBytes, String previousVersion, String previousSha256Hex, String archiveDir) throws IOException {
        String safeVersion = (previousVersion == null || previousVersion.isBlank())
                ? "unknown" : previousVersion.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        Path dir = Path.of(archiveDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path archived = dir.resolve(safeVersion + "-" + previousSha256Hex + ".json");
        Files.write(archived, previousBytes);
        return archived.toString();
    }

    private static ObjectNode buildReport(
            String previousSha256Hex, AuthoringDiffGate.GateResult result, String archivedTo,
            AuthoringMergeGate.MergeResult mergeResult
    ) {
        ObjectNode report = MAPPER.createObjectNode();
        report.put("contractVersion", CONTRACT_VERSION);
        boolean passed = result.passed() && (mergeResult == null || mergeResult.merged());
        report.put("status", passed ? "passed" : "failed");
        report.put("previousModelSha256", "sha256:" + previousSha256Hex);
        if (archivedTo != null) {
            report.put("archivedPreviousModelTo", archivedTo);
        }
        ArrayNode diagnostics = report.putArray("diagnostics");
        for (AuthoringDiffGate.Violation violation : result.violations()) {
            ObjectNode node = diagnostics.addObject();
            node.put("code", violation.code());
            node.put("severity", violation.severity().name().toLowerCase(java.util.Locale.ROOT));
            node.put("message", violation.message());
            if (violation.path() != null) {
                node.put("path", violation.path());
            }
            if (violation.suggestedFix() != null) {
                node.put("suggestedFix", violation.suggestedFix());
            }
        }
        if (mergeResult != null) {
            report.set("merge", buildMergeReport(mergeResult));
        }
        return report;
    }

    /** S5 I5 DoD: "a merged submission is inspectable after the fact -- who contributed which
     *  elements." On refusal, names the colliding/invalid elements via the SAME diagnostic shape
     *  the base gate uses, rather than a second, differently-shaped error format. */
    private static ObjectNode buildMergeReport(AuthoringMergeGate.MergeResult mergeResult) {
        ObjectNode merge = MAPPER.createObjectNode();
        merge.put("status", mergeResult.merged() ? "merged" : "refused");
        ArrayNode diagnostics = merge.putArray("diagnostics");
        for (AuthoringMergeGate.Violation violation : mergeResult.violations()) {
            ObjectNode node = diagnostics.addObject();
            node.put("code", violation.code());
            node.put("message", violation.message());
            if (violation.path() != null) {
                node.put("path", violation.path());
            }
        }
        if (mergeResult.merged()) {
            merge.put("mergedModelVersion", mergeResult.mergedVersion());
            merge.put("mergedModelSha256", "sha256:" + mergeResult.mergedModelSha256Hex());
        }
        merge.set("elementsFromOurs", toElementArray(mergeResult.elementsFromOurs()));
        merge.set("elementsFromTheirs", toElementArray(mergeResult.elementsFromTheirs()));
        return merge;
    }

    private static ArrayNode toElementArray(java.util.Set<ElementDiffer.ElementKey> elements) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (ElementDiffer.ElementKey element : elements) {
            ObjectNode node = array.addObject();
            node.put("arrayKey", element.arrayKey());
            node.put("name", element.name());
        }
        return array;
    }

    /** S5 I5: synthesizes the manifest the Custodian itself writes for a MERGED submission --
     *  never hand-authored -- so the merge outcome is recorded per {@code mergeOutcome} in
     *  {@code authoring-submission.schema.json}. Unions both sides' renames/deliberateRemovals/
     *  securityChanges/couldNotExpress/unchangedButSuspect so nothing either Author declared is
     *  lost by the merge. */
    private static ObjectNode buildMergedManifest(
            String baseSha256Hex, String baseVersion, String oursSha256Hex, String theirsSha256Hex,
            AuthoringMergeGate.MergeResult mergeResult, JsonNode oursManifest, JsonNode theirsManifest
    ) {
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("recordKind", "npdev-authoring-submission.v1");
        manifest.put("previousModelSha256", "sha256:" + baseSha256Hex);
        manifest.put("previousModelVersion", baseVersion == null ? "" : baseVersion);
        manifest.put("submittedModelVersion", mergeResult.mergedVersion());
        manifest.put("request", "Merged from two independent submissions (S5 element-granularity merge).");
        manifest.set("renames", unionArrays(oursManifest, theirsManifest, "renames"));
        manifest.set("deliberateRemovals", unionArrays(oursManifest, theirsManifest, "deliberateRemovals"));
        manifest.set("securityChanges", unionArrays(oursManifest, theirsManifest, "securityChanges"));
        manifest.set("couldNotExpress", unionArrays(oursManifest, theirsManifest, "couldNotExpress"));
        manifest.set("unchangedButSuspect", unionArrays(oursManifest, theirsManifest, "unchangedButSuspect"));

        ObjectNode mergeOutcome = MAPPER.createObjectNode();
        mergeOutcome.put("baseModelSha256", "sha256:" + baseSha256Hex);
        mergeOutcome.put("oursModelSha256", "sha256:" + oursSha256Hex);
        mergeOutcome.put("theirsModelSha256", "sha256:" + theirsSha256Hex);
        mergeOutcome.put("mergedModelSha256", "sha256:" + mergeResult.mergedModelSha256Hex());
        mergeOutcome.put("mergedModelVersion", mergeResult.mergedVersion());
        mergeOutcome.set("elementsFromOurs", toElementArray(mergeResult.elementsFromOurs()));
        mergeOutcome.set("elementsFromTheirs", toElementArray(mergeResult.elementsFromTheirs()));
        manifest.set("mergeOutcome", mergeOutcome);
        return manifest;
    }

    private static ArrayNode unionArrays(JsonNode oursManifest, JsonNode theirsManifest, String key) {
        ArrayNode combined = JsonNodeFactory.instance.arrayNode();
        appendArray(combined, oursManifest, key);
        appendArray(combined, theirsManifest, key);
        return combined;
    }

    private static void appendArray(ArrayNode target, JsonNode manifest, String key) {
        if (manifest == null) {
            return;
        }
        JsonNode array = manifest.get(key);
        if (array != null && array.isArray()) {
            for (JsonNode entry : array) {
                target.add(entry.deepCopy());
            }
        }
    }

    private static String sha256Hex(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is required to hash the previous model", exception);
        }
    }
}
