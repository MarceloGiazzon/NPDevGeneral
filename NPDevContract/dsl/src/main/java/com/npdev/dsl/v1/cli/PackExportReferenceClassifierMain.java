package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.PackExportReferenceClassifier;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PACK-14: the CLI-side entry point for the single reference-classification implementation, so the
 * Python {@code npdev pack export} can thin-wrap this instead of carrying its own copy of the rules.
 *
 * <p>Reads a JSON document from the first argument (a file path) or, when absent, stdin, of shape
 * {@code {"concepts": [...], "exportSet": [...], "packsRoot": "..."}} and writes the classified
 * result to stdout: the concepts with rewrites applied in place, plus {@code rewrites},
 * {@code crossPackVersions} and {@code unresolved}.</p>
 */
public final class PackExportReferenceClassifierMain {

    private PackExportReferenceClassifierMain() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = (args.length > 0)
                ? mapper.readTree(new File(args[0]))
                : mapper.readTree(System.in);

        List<ObjectNode> concepts = new ArrayList<>();
        for (JsonNode concept : input.path("concepts")) {
            concepts.add((ObjectNode) concept.deepCopy());
        }
        Set<String> exportSet = new LinkedHashSet<>();
        for (JsonNode name : input.path("exportSet")) {
            if (!name.asText().isBlank()) {
                exportSet.add(name.asText());
            }
        }
        Path packsRoot = Path.of(input.path("packsRoot").asText("."));

        PackExportReferenceClassifier.Result result =
                PackExportReferenceClassifier.classify(concepts, exportSet, packsRoot);

        ObjectNode out = mapper.createObjectNode();
        ArrayNode conceptsArray = out.putArray("concepts");
        for (ObjectNode concept : concepts) {
            conceptsArray.add(concept);
        }
        out.set("rewrites", mapArray(mapper, result.rewrites()));
        out.set("unresolved", mapArray(mapper, result.unresolved()));
        ObjectNode cross = out.putObject("crossPackVersions");
        result.crossPackVersions().forEach(cross::put);
        mapper.writeValue(System.out, out);
    }

    private static ArrayNode mapArray(ObjectMapper mapper, List<java.util.Map<String, String>> entries) {
        ArrayNode array = mapper.createArrayNode();
        for (java.util.Map<String, String> entry : entries) {
            ObjectNode node = array.addObject();
            entry.forEach(node::put);
        }
        return array;
    }
}
