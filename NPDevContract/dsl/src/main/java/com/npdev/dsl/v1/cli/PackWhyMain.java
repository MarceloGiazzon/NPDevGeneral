package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.NetworkPolicy;
import com.npdev.dsl.v1.parser.ModelSourceResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code npdev pack why <packId>}: always a fresh live computation (re-runs discovery + MVS's
 * constraint-collection, never reads persisted bookkeeping from {@code npdev.lock} -- the lock's
 * own on-disk shape stays the minimal 3-field-per-packId shape the card specifies, with no "why"
 * bloat committed to the repo). Prints every {@code {requirer, path, constraint}} that contributed
 * to {@code packId}'s selection.
 */
public final class PackWhyMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PackWhyMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String modelArg = null;
        String packIdArg = null;
        String outArg = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--out".equals(arg) && i + 1 < args.length) {
                outArg = args[++i];
            } else if (arg.startsWith("--out=")) {
                outArg = arg.substring("--out=".length());
            } else if (!arg.startsWith("--")) {
                if (modelArg == null) {
                    modelArg = arg;
                } else {
                    packIdArg = arg;
                }
            }
        }
        if (modelArg == null || packIdArg == null) {
            System.err.println("usage: PackWhyMain <model.json> <packId> [--out report.json]");
            return 64;
        }

        ObjectNode report = MAPPER.createObjectNode();
        String packId = packIdArg;
        try {
            Path modelPath = Path.of(modelArg);
            // PK-5: DENIED -- why is always a fresh live computation but never fetches.
            ModelSourceResolver.PackCliResolution resolution =
                    new ModelSourceResolver().resolvePackGraphForCli(modelPath, NetworkPolicy.DENIED);
            List<String> reasons = resolution.whyDescriptionsByPackId().get(packId);
            if (reasons == null) {
                report.put("status", "failed");
                report.put("error", "pack '" + packId + "' is not part of this model's resolved pack graph");
            } else {
                report.put("status", "ok");
                report.put("packId", packId);
                ArrayNode reasonsNode = report.putArray("requiredBy");
                reasons.forEach(reasonsNode::add);
                if (reasons.isEmpty()) {
                    report.put("note", "no transitive requirement named this pack -- it is only a direct app import");
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
}
