package com.npdev.generator.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code npdev import genexus} -- IMP-2. Reads a draft made by {@code npdev import db} (model.json and
 * import-report.json in {@code --draft}) and the GeneXus exports, and writes the enriched model, the enriched
 * report and the manual-port source files into {@code --out}. Exit codes: 0 = written, 2 = could not.
 */
public final class EnrichGeneXusMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private EnrichGeneXusMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String draft = null;
        String tables = null;
        String module = null;
        String outDir = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            boolean hasValue = i + 1 < args.length;
            if ("--draft".equals(arg) && hasValue) {
                draft = args[++i];
            } else if ("--tables-xml".equals(arg) && hasValue) {
                tables = args[++i];
            } else if ("--module-xml".equals(arg) && hasValue) {
                module = args[++i];
            } else if ("--out".equals(arg) && hasValue) {
                outDir = args[++i];
            } else {
                err.println("unknown or incomplete argument: " + arg);
                usage(err);
                return 2;
            }
        }
        if (draft == null || tables == null || outDir == null) {
            usage(err);
            return 2;
        }
        try {
            ObjectNode model = (ObjectNode) MAPPER.readTree(Files.readString(Path.of(draft, "model.json"), StandardCharsets.UTF_8));
            ObjectNode report = (ObjectNode) MAPPER.readTree(Files.readString(Path.of(draft, "import-report.json"), StandardCharsets.UTF_8));
            GeneXusEnricher.Result result = GeneXusEnricher.enrich(model, report, parse(Path.of(tables)),
                    module == null ? null : parse(Path.of(module)));
            Path dir = Path.of(outDir);
            Files.createDirectories(dir);
            write(dir.resolve("model.json"), PRETTY.writeValueAsString(result.model()));
            write(dir.resolve("import-report.json"), PRETTY.writeValueAsString(result.report()));
            for (Map.Entry<String, String> file : result.files().entrySet()) {
                Path target = dir.resolve(file.getKey()).normalize();
                if (!target.startsWith(dir.normalize())) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                write(target, file.getValue());
            }
            var counts = result.report().path("genexus").path("counts");
            out.println("npdev import genexus: " + counts.path("attributes").path("captionsApplied").asInt() + " caption(s), "
                    + counts.path("domains").path("enumAppliedToFields").asInt() + " enum field(s), "
                    + counts.path("transactions").path("mapped").asInt() + "/" + counts.path("transactions").path("total").asInt()
                    + " transaction(s) fully mapped, " + counts.path("rules").path("invariantTranslated").asInt() + " invariant candidate(s) translated, "
                    + counts.path("rules").path("invariantSourceOnly").asInt() + " source-only.");
            out.println("  model:  " + dir.resolve("model.json").toAbsolutePath());
            out.println("  report: " + dir.resolve("import-report.json").toAbsolutePath());
            return 0;
        } catch (IOException | SAXException | ParserConfigurationException | RuntimeException exception) {
            err.println("npdev import genexus: " + exception.getMessage());
            return 2;
        }
    }

    private static Document parse(Path file) throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var in = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text + "\n", StandardCharsets.UTF_8);
    }

    private static void usage(PrintStream err) {
        err.println("usage: EnrichGeneXusMain --draft <dir with model.json + import-report.json> --tables-xml <tables/attributes export> "
                + "[--module-xml <module export>] --out <dir>");
    }
}
