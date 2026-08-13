package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.parser.ModelSourceResolver;

import java.io.IOException;
import java.nio.file.Path;

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
            ModelSourceResolver.PackCliResolution resolution =
                    new ModelSourceResolver().resolvePackGraphForCli(modelPath);
            PackLockFile.of(resolution.lockEntries()).write(resolution.rootDirectory());

            report.put("status", "ok");
            ObjectNode packsNode = report.putObject("packs");
            resolution.lockEntries().forEach((packId, locked) -> {
                ObjectNode entry = packsNode.putObject(packId);
                entry.put("resolvedVersion", locked.resolvedVersion());
                entry.put("sourcePath", locked.sourcePath());
                entry.put("digest", locked.digest());
            });
            report.put("lockFile", resolution.rootDirectory().resolve(PackLockFile.FILE_NAME).toString());
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
}
