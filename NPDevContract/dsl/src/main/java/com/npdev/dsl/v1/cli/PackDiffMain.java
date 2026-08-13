package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.PackChangeClassification;
import com.npdev.dsl.v1.pack.PackDiffEngine;
import com.npdev.dsl.v1.pack.PackDiffFinding;
import com.npdev.dsl.v1.pack.PackDiffResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * PK-4 Stage A CLI entry point ({@code npdev pack diff <oldPackFile> <newPackFile>}). Reads the two
 * pack.json files named on the command line, hands them to the pure {@link PackDiffEngine}, and
 * prints a typed JSON report of every classified difference. Purely informational -- unlike {@code
 * PackPublishMain} (Stage B), this never refuses anything; exit code is 0 whenever the two files
 * were readable and diffable, whatever the classification turns out to be.
 *
 * <p>Wired from Gradle as {@code :NPDevContract:dsl:packDiff} (see {@code
 * NPDevContract/dsl/build.gradle}) and from the CLI as {@code npdev pack diff} (see
 * {@code NPDevCli/npdev_cli.py}'s {@code pack} subparser group), mirroring {@link
 * ModelValidatorMain}'s own Gradle-JavaExec-wrapped-by-a-thin-Python-subparser shape.
 */
public final class PackDiffMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_VERSION = "npdev-pack-diff-report.v1";

    private PackDiffMain() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ENGLISH);

        String oldArg = null;
        String newArg = null;
        String outArg = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--out".equals(arg) && i + 1 < args.length) {
                outArg = args[++i];
            } else if (arg.startsWith("--out=")) {
                outArg = arg.substring("--out=".length());
            } else if (!arg.startsWith("--")) {
                if (oldArg == null) {
                    oldArg = arg;
                } else if (newArg == null) {
                    newArg = arg;
                }
            }
        }
        if (oldArg == null || newArg == null) {
            System.err.println("usage: PackDiffMain <oldPack.json> <newPack.json> [--out report.json]");
            System.exit(64);
            return;
        }

        JsonNode oldPack;
        JsonNode newPack;
        try {
            oldPack = readJson(oldArg);
            newPack = readJson(newArg);
        } catch (IOException | RuntimeException readError) {
            System.err.println("failed to read pack file: " + safeMessage(readError));
            System.exit(66);
            return;
        }

        PackDiffResult result;
        try {
            result = PackDiffEngine.diff(oldPack, newPack);
        } catch (IllegalArgumentException invalid) {
            System.err.println("invalid pack document: " + safeMessage(invalid));
            System.exit(65);
            return;
        }

        ObjectNode report = buildReport(oldArg, newArg, result);
        String json = serialize(report);
        if (outArg != null) {
            writeReport(outArg, json);
        }
        System.out.println(json);
        System.exit(0);
    }

    private static JsonNode readJson(String path) throws IOException {
        Path resolved = Path.of(path);
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("file not found: " + resolved);
        }
        return MAPPER.readTree(resolved.toFile());
    }

    private static ObjectNode buildReport(String oldArg, String newArg, PackDiffResult result) {
        ObjectNode report = MAPPER.createObjectNode();
        report.put("contractVersion", CONTRACT_VERSION);
        report.put("oldPack", oldArg);
        report.put("newPack", newArg);
        report.put("overallClassification", result.worstClassification().map(PackChangeClassification::name).orElse(null));
        ArrayNode findings = report.putArray("findings");
        for (PackDiffFinding finding : result.findings()) {
            ObjectNode findingNode = findings.addObject();
            findingNode.put("section", finding.section());
            findingNode.put("path", finding.path());
            findingNode.put("classification", finding.classification().name());
            findingNode.put("message", finding.message());
        }
        return report;
    }

    private static String serialize(ObjectNode report) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (IOException serializationError) {
            throw new IllegalStateException("failed to serialize pack diff report", serializationError);
        }
    }

    private static void writeReport(String outArg, String json) {
        try {
            Path outPath = Path.of(outArg);
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            Files.writeString(outPath, json + System.lineSeparator());
        } catch (IOException writeError) {
            System.err.println("failed to write report to " + outArg + ": " + safeMessage(writeError));
            System.exit(70);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
