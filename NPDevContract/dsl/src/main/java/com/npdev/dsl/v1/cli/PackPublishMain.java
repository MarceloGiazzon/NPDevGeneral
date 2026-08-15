package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.PackChangeClassification;
import com.npdev.dsl.v1.pack.PackDiffFinding;
import com.npdev.dsl.v1.pack.PackPublishGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * PK-4 Stage B CLI entry point ({@code npdev pack publish <oldPackFile> <newPackFile>}). Runs
 * {@link PackPublishGate#evaluate}, prints a typed JSON decision report, and (only with {@code
 * --write}, mirroring the established {@code npdev migrate ... --write} convention -- see {@code
 * NPDevCli/npdev_cli.py}'s {@code migrate dsl-2}/{@code migrate rename} subcommands: without it,
 * this reports what would happen and exits; with it, the change is actually applied) rewrites
 * {@code newPackFile} in place with an empty {@code migrations} chain entry when {@link
 * PackPublishGate.Decision#shouldWriteEmptyMigrationEntry()} says one belongs there.
 *
 * <p>Exit codes: {@code 0} when the publish is allowed, {@code 2} when refused (a version bump
 * smaller than the diff requires, or a downgrade), {@code 64} on usage error, {@code 65} on an
 * unreadable version or malformed pack document, {@code 66} when a pack file cannot be read.
 *
 * <p>Wired from Gradle as {@code :NPDevContract:dsl:packPublish} and from the CLI as
 * {@code npdev pack publish}, the same shape as {@link PackDiffMain} and {@link ModelValidatorMain}.
 */
public final class PackPublishMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_VERSION = "npdev-pack-publish-report.v1";

    private PackPublishMain() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ENGLISH);

        String oldArg = null;
        String newArg = null;
        String outArg = null;
        boolean write = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--out".equals(arg) && i + 1 < args.length) {
                outArg = args[++i];
            } else if (arg.startsWith("--out=")) {
                outArg = arg.substring("--out=".length());
            } else if ("--write".equals(arg)) {
                write = true;
            } else if (!arg.startsWith("--")) {
                if (oldArg == null) {
                    oldArg = arg;
                } else if (newArg == null) {
                    newArg = arg;
                }
            }
        }
        if (oldArg == null || newArg == null) {
            System.err.println("usage: PackPublishMain <oldPack.json> <newPack.json> [--out report.json] [--write]");
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

        PackPublishGate.Decision decision;
        try {
            decision = PackPublishGate.evaluate(oldPack, newPack);
        } catch (IllegalArgumentException invalid) {
            System.err.println("cannot evaluate publish: " + safeMessage(invalid));
            System.exit(65);
            return;
        }

        ObjectNode report = buildReport(oldArg, newArg, decision);
        String json = serialize(report);
        if (outArg != null) {
            writeReport(outArg, json);
        }
        System.out.println(json);
        System.err.println(decision.message());

        if (!decision.allowed()) {
            System.exit(2);
            return;
        }

        if (write) {
            JsonNode updated = newPack;
            boolean changed = false;
            if (decision.shouldWriteEmptyMigrationEntry()) {
                String oldVersion = oldPack.get("version").asText();
                String newVersion = newPack.get("version").asText();
                updated = PackPublishGate.withEmptyMigrationChainEntry(updated, oldVersion, newVersion);
                System.err.println("Wrote empty migration chain entry '" + oldVersion + " -> " + newVersion + "' into " + newArg);
                changed = true;
            }
            // REG-151: stamp/propagate the firstPublishedVersion trust anchor on every successful
            // --write, not only when a migration entry is also written -- a pack with no chain yet
            // still needs its anchor pinned the first time it publishes at all.
            JsonNode beforeAnchor = updated;
            updated = PackPublishGate.withFirstPublishedVersionAnchor(oldPack, updated);
            if (!updated.equals(beforeAnchor)) {
                System.err.println("Wrote firstPublishedVersion anchor '" + updated.get("firstPublishedVersion").asText() + "' into " + newArg);
                changed = true;
            }
            if (changed) {
                writePack(newArg, updated);
            }
        }
        System.exit(0);
    }

    private static JsonNode readJson(String path) throws IOException {
        Path resolved = Path.of(path);
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("file not found: " + resolved);
        }
        return MAPPER.readTree(resolved.toFile());
    }

    private static ObjectNode buildReport(String oldArg, String newArg, PackPublishGate.Decision decision) {
        ObjectNode report = MAPPER.createObjectNode();
        report.put("contractVersion", CONTRACT_VERSION);
        report.put("oldPack", oldArg);
        report.put("newPack", newArg);
        report.put("allowed", decision.allowed());
        report.put("requiredBump", decision.requiredBump().name());
        report.put("actualBump", decision.actualBump().name());
        report.put("overallClassification",
                decision.overallClassification().map(PackChangeClassification::name).orElse(null));
        report.put("message", decision.message());
        ArrayNode findings = report.putArray("findings");
        for (PackDiffFinding finding : decision.findings()) {
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
            throw new IllegalStateException("failed to serialize pack publish report", serializationError);
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

    private static void writePack(String path, JsonNode updatedPack) {
        try {
            Files.writeString(Path.of(path), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(updatedPack)
                    + System.lineSeparator());
        } catch (IOException writeError) {
            System.err.println("failed to write updated pack to " + path + ": " + safeMessage(writeError));
            System.exit(70);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
