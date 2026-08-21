package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.NetworkPolicy;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.pack.PackSignature;
import com.npdev.dsl.v1.pack.PackSigner;
import com.npdev.dsl.v1.parser.ModelSourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code npdev pack add} / {@code npdev pack update}: runs the live discovery+MVS pass over a
 * model's {@code packs[]} graph and (re)writes {@code npdev.lock} to match. Both commands are the
 * same operation -- the CLI-facing distinction (a brand-new dependency vs. refreshing an existing
 * lock) is purely UX framing, not a different underlying mechanism; see {@link PackUpdateMain},
 * which delegates here.
 *
 * <p>Prints a single JSON report to stdout (and, with {@code --out}, also writes it to a file) --
 * same convention as {@link ModelValidatorMain}. Exit {@code 0} on success, {@code 2} when the
 * graph itself refuses (cycle, MVS conflict, depth/fan-out cap, unbound requires), {@code 64} on
 * usage error.
 */
public final class PackAddMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PackAddMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String modelArg = null;
        String outArg = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--out".equals(arg) && i + 1 < args.length) {
                outArg = args[++i];
            } else if (arg.startsWith("--out=")) {
                outArg = arg.substring("--out=".length());
            } else if (!arg.startsWith("--")) {
                modelArg = arg;
            }
        }
        if (modelArg == null) {
            System.err.println("usage: PackAddMain <model.json> [--out report.json]");
            return 64;
        }

        ObjectNode report = MAPPER.createObjectNode();
        try {
            Path modelPath = Path.of(modelArg);
            // PK-5 step 2: this is one of the two call sites (with PackUpdateMain, which delegates
            // here) allowed to pass ALLOWED -- the explicit network phase a `from`-based remote
            // pack's fetch happens during.
            ModelSourceResolver.PackCliResolution resolution =
                    new ModelSourceResolver().resolvePackGraphForCli(modelPath, NetworkPolicy.ALLOWED);
            // PACK-8 Step 5: verify signatures on every resolved pack that carries one.
            // A pack with no signature field is allowed (warn mode) -- the trust model is
            // "verify when present, warn when absent". A pack WITH a signature that fails
            // verification is a hard refusal.
            List<String> signatureWarnings = verifyPackSignatures(resolution);
            if (!signatureWarnings.isEmpty()) {
                // Hard refusal: any signature that failed verification
                List<String> failures = signatureWarnings.stream()
                        .filter(w -> w.startsWith("FAIL"))
                        .toList();
                if (!failures.isEmpty()) {
                    throw new IOException("pack signature verification failed:\n  " + String.join("\n  ", failures));
                }
            }

            PackLockFile.of(resolution.lockEntries()).write(resolution.rootDirectory());

            report.put("status", "ok");
            ObjectNode packsNode = report.putObject("packs");
            resolution.lockEntries().forEach((packId, locked) -> {
                ObjectNode entry = packsNode.putObject(packId);
                entry.put("resolvedVersion", locked.resolvedVersion());
                entry.put("sourcePath", locked.sourcePath());
                entry.put("digest", locked.digest());
                if (!locked.from().isEmpty()) {
                    entry.put("from", locked.from());
                }
            });
            report.put("lockFile", resolution.rootDirectory().resolve(PackLockFile.FILE_NAME).toString());

            // Report signature verification results
            if (!signatureWarnings.isEmpty()) {
                var warningsNode = report.putArray("signatureWarnings");
                for (String warning : signatureWarnings) {
                    warningsNode.add(warning);
                }
            }
        } catch (IOException failure) {
            report.put("status", "failed");
            report.put("error", failure.getMessage());
        }

        String json = ReportIo.serialize(MAPPER, report);
        if (outArg != null) {
            ReportIo.write(outArg, json);
        }
        System.out.println(json);
        return "failed".equals(report.get("status").asText()) ? 2 : 0;
    }

    /**
     * PACK-8 Step 5: for every resolved pack in the graph, read its pack.json and check for a
     * {@code signature} field. If present, verify it against the lock entry's digest (which is the
     * same tree hash the cache computed during fetch). If verification fails, the result starts
     * with {@code "FAIL"} and is a hard refusal. If no signature is present, the result starts with
     * {@code "WARN"} -- trust mode is warn-by-default, so unsigned packs are allowed but reported.
     */
    private static List<String> verifyPackSignatures(ModelSourceResolver.PackCliResolution resolution) {
        List<String> results = new ArrayList<>();
        resolution.lockEntries().forEach((packId, locked) -> {
            Path packJsonPath = Path.of(locked.sourcePath());
            if (!Files.isRegularFile(packJsonPath)) {
                return; // local packs without a source path in the cache are not checked
            }
            try {
                JsonNode packJson = MAPPER.readTree(packJsonPath.toFile());
                JsonNode sigNode = packJson.get("signature");
                if (sigNode == null || !sigNode.isObject()) {
                    results.add("WARN: pack '" + packId + "' has no signature (unsigned pack, allowed in warn mode)");
                    return;
                }

                String algorithm = sigNode.has("algorithm") ? sigNode.get("algorithm").asText() : null;
                String digest = sigNode.has("digest") ? sigNode.get("digest").asText() : null;
                String value = sigNode.has("value") ? sigNode.get("value").asText() : null;
                String publicKey = sigNode.has("publicKey") ? sigNode.get("publicKey").asText() : null;

                if (algorithm == null || digest == null || value == null || publicKey == null) {
                    results.add("FAIL: pack '" + packId + "' has an incomplete signature (missing required fields)");
                    return;
                }

                PackSignature signature = new PackSignature(algorithm, digest, value, publicKey);
                String expectedDigest = "sha256:" + locked.digest();

                if (PackSigner.verify(signature, expectedDigest)) {
                    results.add("OK: pack '" + packId + "' signature verified (" + algorithm + ")");
                } else {
                    results.add("FAIL: pack '" + packId + "' signature verification failed -- "
                            + "the pack content does not match the signed digest (tampered or corrupted)");
                }
            } catch (Exception e) {
                results.add("FAIL: pack '" + packId + "' signature check error: " + e.getMessage());
            }
        });
        return results;
    }
}
