package com.npdev.generator.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--previous" -> previousPath = args[++i];
                case "--submitted" -> submittedPath = args[++i];
                case "--manifest" -> manifestPath = args[++i];
                case "--out" -> outPath = args[++i];
                case "--archiveDir" -> archiveDir = args[++i];
                default -> {
                    System.err.println("Unrecognized argument: " + args[i]
                            + " (supported: --previous, --submitted, --manifest, --out, --archiveDir)");
                    System.exit(64);
                    return;
                }
            }
        }
        if (previousPath == null || submittedPath == null) {
            System.err.println("usage: AuthoringDiffGateMain --previous <model.json> --submitted <model.json> "
                    + "[--manifest <manifest.json>] [--out <report.json>]");
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

        ObjectNode report = buildReport(previousSha256Hex, result, archivedTo);
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        if (outPath != null) {
            Path out = Path.of(outPath).toAbsolutePath().normalize();
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, json, StandardCharsets.UTF_8);
        }
        System.out.println(json);
        System.exit(result.passed() ? 0 : 2);
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

    private static ObjectNode buildReport(String previousSha256Hex, AuthoringDiffGate.GateResult result, String archivedTo) {
        ObjectNode report = MAPPER.createObjectNode();
        report.put("contractVersion", CONTRACT_VERSION);
        report.put("status", result.passed() ? "passed" : "failed");
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
        return report;
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
