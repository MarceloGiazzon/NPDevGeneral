package com.npdev.dsl.v1.cli;

import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone entry point that resolves a model's {@code packs[]}/fragment references and prints
 * the resulting pack-composed JSON -- the SAME representation {@code RuntimeApiEmitter} writes to
 * a generated app's own {@code npdev-generated/src/main/resources/npdev/model.json}
 * ({@code resolvedModelSource.resolvedModelJson()}), which is in turn the file
 * {@code ModelSyncStatusService}'s {@code npdev.deploy.model-path} reads at runtime.
 *
 * <p>Exists so a caller that wants to ask "is this app's authoring source still in sync with what
 * it's running" can produce a byte-comparable candidate WITHOUT a full {@code npdev generate app}
 * (which writes an entire output tree) -- NPDevRuntimeHost's {@code ModelSyncStatusController}
 * only ever hashes whatever JSON it is handed; it does no pack resolution of its own. Posting a
 * RAW, unresolved {@code definition/model.json} to that endpoint would report every app using
 * packs or fragments as permanently "diverged," since the deployed side is always the
 * pack-RESOLVED form. This tool is the missing step in between.
 *
 * <p>Deliberately does nothing else: no structural/semantic validation ({@link ModelValidatorMain}
 * already owns that, and the intended calling convention is "validate, then canonicalize, then
 * POST" -- duplicating the check here would just mean two places to keep in sync). A model whose
 * pack graph cannot be resolved at all (missing pack, cycle, lock mismatch) fails loudly here,
 * the same way it would fail generation.
 *
 * <p>Exit {@code 0} with the resolved JSON on stdout (and, with {@code --out}, also written to a
 * file); {@code 2} when resolution fails; {@code 64} on usage error -- same convention every other
 * CLI Main class in this package uses.
 */
public final class ModelCanonicalizeMain {

    private ModelCanonicalizeMain() {
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
            System.err.println("usage: ModelCanonicalizeMain <model.json> [--out canonical.json]");
            return 64;
        }

        try {
            ResolvedModelSource resolved = new ModelSourceResolver().resolve(Path.of(modelArg));
            String json = resolved.resolvedModelJson();
            if (outArg != null) {
                writeOut(outArg, json);
            }
            System.out.print(json);
            return 0;
        } catch (IOException | RuntimeException failure) {
            System.err.println("failed to resolve " + modelArg + ": " + safeMessage(failure));
            return 2;
        }
    }

    private static void writeOut(String outArg, String json) throws IOException {
        Path outPath = Path.of(outArg);
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        Files.writeString(outPath, json);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
