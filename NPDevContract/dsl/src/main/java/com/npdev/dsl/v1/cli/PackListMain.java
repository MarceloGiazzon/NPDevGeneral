package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.parser.ModelSourceResolver;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code npdev pack list}: prints the current {@code npdev.lock} if one exists (the committed,
 * authoritative record); otherwise runs a live dry-run discovery+MVS pass and labels the output
 * {@code "locked": false} so the difference is never ambiguous.
 */
public final class PackListMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PackListMain() {
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
            System.err.println("usage: PackListMain <model.json> [--out report.json]");
            return 64;
        }

        ObjectNode report = MAPPER.createObjectNode();
        try {
            Path modelPath = Path.of(modelArg).toAbsolutePath().normalize();
            Path rootDirectory = modelPath.getParent();
            ObjectNode packsNode = report.putObject("packs");
            if (PackLockFile.exists(rootDirectory)) {
                report.put("locked", true);
                PackLockFile lock = PackLockFile.read(rootDirectory);
                lock.packs().forEach((packId, locked) -> {
                    ObjectNode entry = packsNode.putObject(packId);
                    entry.put("resolvedVersion", locked.resolvedVersion());
                    entry.put("sourcePath", locked.sourcePath());
                    entry.put("digest", locked.digest());
                });
            } else {
                report.put("locked", false);
                report.put("note", "not locked -- run 'npdev pack add'");
                ModelSourceResolver.PackCliResolution resolution =
                        new ModelSourceResolver().resolvePackGraphForCli(modelPath);
                resolution.lockEntries().forEach((packId, locked) -> {
                    ObjectNode entry = packsNode.putObject(packId);
                    entry.put("resolvedVersion", locked.resolvedVersion());
                    entry.put("sourcePath", locked.sourcePath());
                });
            }
            report.put("status", "ok");
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
