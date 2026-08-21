package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.PackSealednessAnalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code npdev pack seal}: certifies a pack is sealed -- eligible to ship as a precompiled jar.
 * Reads the pack's own {@code pack.json}, runs {@link PackSealednessAnalyzer#analyze(JsonNode,
 * PackSealednessAnalyzer.PackDependencyResolver)} (so composed sealed packs -- a pack with its own
 * {@code packs[]} -- are verified across the whole transitive closure), and on success writes a {@code
 * .sealed} marker next to the pack.json. A pack that is not sealed prints its named violations and
 * exits non-zero (64 = usage error, 2 = not sealed/failed).
 *
 * <p>Transitive dependencies are resolved offline from a local packs root (default: the pack.json's
 * sibling {@code packs/} directory, or the {@code --packs-root} argument). A {@code $ref} entry is
 * read relative to the depending pack's own directory; a bare {@code pack} entry is read from
 * {@code <packsRoot>/<packId>/pack.json}. A remote {@code from} coordinate cannot be fetched here
 * (sealing runs offline, like generate) -- it must already exist under the packs root or the seal
 * is refused with a clear message, which is the correct outcome: an unresolvable dependency cannot
 * be proven sealed.
 */
public final class PackSealMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PackSealMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** A filesystem-backed dependency resolver rooted at a local packs directory. */
    private static final class LocalPacksResolver implements PackSealednessAnalyzer.PackDependencyResolver {
        private final Path packsRoot;

        LocalPacksResolver(Path packsRoot) {
            this.packsRoot = packsRoot;
        }

        private JsonNode read(Path path) {
            try {
                return MAPPER.readTree(path.toFile());
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read dependency pack at " + path, e);
            }
        }

        @Override
        public JsonNode resolve(String ref) {
            if (ref.startsWith("$ref:")) {
                // handled by the caller (relative-path form) -- not expected here, but be safe
                return null;
            }
            // A bare pack id: <packsRoot>/<id>/pack.json
            Path candidate = packsRoot.resolve(ref).resolve("pack.json");
            if (Files.isRegularFile(candidate)) {
                return read(candidate);
            }
            // A literal filesystem path (relative to packsRoot or a relative-to-caller path).
            Path direct = packsRoot.resolve(ref);
            if (Files.isRegularFile(direct)) {
                return read(direct);
            }
            return null;
        }
    }

    static int run(String[] args) {
        String packPath = null;
        Path packsRoot = null;
        String outArg = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--packs-root".equals(arg) && i + 1 < args.length) {
                packsRoot = Path.of(args[++i]).toAbsolutePath().normalize();
            } else if (arg.startsWith("--packs-root=")) {
                packsRoot = Path.of(arg.substring("--packs-root=".length())).toAbsolutePath().normalize();
            } else if ("--out".equals(arg) && i + 1 < args.length) {
                outArg = args[++i];
            } else if (arg.startsWith("--out=")) {
                outArg = arg.substring("--out=".length());
            } else if (!arg.startsWith("--")) {
                packPath = arg;
            }
        }
        if (packPath == null) {
            System.err.println("usage: PackSealMain <pack.json> [--packs-root <dir>] [--out report.json]");
            return 64;
        }

        ObjectNode report = MAPPER.createObjectNode();
        try {
            Path packFile = Path.of(packPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(packFile)) {
                report.put("status", "failed");
                report.put("error", "pack.json not found: " + packFile);
            } else {
                JsonNode packJson = MAPPER.readTree(packFile.toFile());
                Path root = packsRoot == null ? packFile.getParent().resolve("packs") : packsRoot;
                PackSealednessAnalyzer.PackDependencyResolver resolver = new LocalPacksResolver(root);
                PackSealednessAnalyzer.SealednessResult result =
                        PackSealednessAnalyzer.analyze(packJson, resolver);

                report.put("pack", textOrNull(packJson.get("pack"), packFile.getFileName().toString()));
                report.put("sealed", result.sealed());
                if (result.sealed()) {
                    Path marker = packFile.resolveSibling(bareName(packFile.getFileName().toString()) + ".sealed");
                    Files.writeString(marker, "sealed-as-of-2026-08-21\n");
                    report.put("marker", marker.toString());
                } else {
                    var violations = report.putArray("violations");
                    for (String v : result.violations()) {
                        violations.add(v);
                    }
                }
                report.put("status", "ok");
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

        if ("failed".equals(report.get("status").asText())) {
            return 2;
        }
        JsonNode sealed = report.get("sealed");
        if (sealed != null && !sealed.asBoolean()) {
            return 2;
        }
        return 0;
    }

    private static String textOrNull(JsonNode node, String fallback) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return fallback;
        }
        return node.asText();
    }

    private static String bareName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}