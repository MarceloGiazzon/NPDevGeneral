package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.PackChangeClassification;
import com.npdev.dsl.v1.pack.PackDiffFinding;
import com.npdev.dsl.v1.pack.PackPublishGate;
import com.npdev.dsl.v1.pack.PackSignature;
import com.npdev.dsl.v1.pack.PackSigner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PK-4 Stage B CLI entry point ({@code npdev pack publish}). Two modes of operation:
 *
 * <h3>Gate mode (existing, PK-4)</h3>
 * {@code npdev pack publish <oldPackFile> <newPackFile>} -- runs {@link PackPublishGate#evaluate},
 * prints a typed JSON decision report, and (only with {@code --write}) rewrites {@code newPackFile}
 * in place with an empty {@code migrations} chain entry.
 *
 * <h3>Artifact mode (PACK-8 Step 6)</h3>
 * {@code npdev pack publish --pack-dir <path> --output <dir>} -- validates the pack, computes its
 * content digest, optionally signs it, and produces an OCI artifact (manifest + zip blob) in the
 * output directory. With {@code --sign <keypair-path>}, the digest is signed with the given keypair
 * and the signature is embedded in the pack.json before zipping. With {@code --dry-run}, prints
 * what would be published without writing anything.
 *
 * <p>Exit codes: {@code 0} on success, {@code 2} when the publish gate refuses, {@code 64} on
 * usage error, {@code 65} on an unreadable or malformed pack, {@code 66} when a file cannot be
 * read, {@code 70} on write failure.
 */
public final class PackPublishMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_VERSION = "npdev-pack-publish-report.v1";

    private PackPublishMain() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ENGLISH);
        System.exit(run(args));
    }

    static int run(String[] args) {
        // Parse arguments
        String oldArg = null;
        String newArg = null;
        String outArg = null;
        String packDirArg = null;
        String outputArg = null;
        String signArg = null;
        boolean write = false;
        boolean dryRun = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--out".equals(arg) && i + 1 < args.length) {
                outArg = args[++i];
            } else if (arg.startsWith("--out=")) {
                outArg = arg.substring("--out=".length());
            } else if ("--write".equals(arg)) {
                write = true;
            } else if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else if ("--pack-dir".equals(arg) && i + 1 < args.length) {
                packDirArg = args[++i];
            } else if (arg.startsWith("--pack-dir=")) {
                packDirArg = arg.substring("--pack-dir=".length());
            } else if ("--output".equals(arg) && i + 1 < args.length) {
                outputArg = args[++i];
            } else if (arg.startsWith("--output=")) {
                outputArg = arg.substring("--output=".length());
            } else if ("--sign".equals(arg) && i + 1 < args.length) {
                signArg = args[++i];
            } else if (arg.startsWith("--sign=")) {
                signArg = arg.substring("--sign=".length());
            } else if (!arg.startsWith("--")) {
                if (oldArg == null) {
                    oldArg = arg;
                } else if (newArg == null) {
                    newArg = arg;
                }
            }
        }

        // Artifact mode: --pack-dir was provided
        if (packDirArg != null) {
            return runArtifactMode(packDirArg, outputArg, signArg, dryRun, outArg);
        }

        // Gate mode: positional old/new pack args
        if (oldArg == null || newArg == null) {
            System.err.println("usage: PackPublishMain <oldPack.json> <newPack.json> [--out report.json] [--write]");
            System.err.println("   or: PackPublishMain --pack-dir <dir> --output <dir> [--sign <keypair>] [--dry-run]");
            return 64;
        }

        return runGateMode(oldArg, newArg, write, outArg);
    }

    // ---- Gate mode (PK-4, existing behavior) ---------------------------------------------------

    private static int runGateMode(String oldArg, String newArg, boolean write, String outArg) {
        JsonNode oldPack;
        JsonNode newPack;
        try {
            oldPack = readJson(oldArg);
            newPack = readJson(newArg);
        } catch (IOException | RuntimeException readError) {
            System.err.println("failed to read pack file: " + safeMessage(readError));
            return 66;
        }

        PackPublishGate.Decision decision;
        try {
            decision = PackPublishGate.evaluate(oldPack, newPack);
        } catch (IllegalArgumentException invalid) {
            System.err.println("cannot evaluate publish: " + safeMessage(invalid));
            return 65;
        }

        ObjectNode report = buildReport(oldArg, newArg, decision);
        String json = serialize(report);
        if (outArg != null) {
            writeReport(outArg, json);
        }
        System.out.println(json);
        System.err.println(decision.message());

        if (!decision.allowed()) {
            return 2;
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
        return 0;
    }

    // ---- Artifact mode (PACK-8 Step 6) ----------------------------------------------------------

    private static int runArtifactMode(String packDirArg, String outputArg, String signArg,
                                        boolean dryRun, String outArg) {
        Path packDir = Path.of(packDirArg);
        if (!Files.isDirectory(packDir)) {
            System.err.println("pack directory does not exist: " + packDir);
            return 66;
        }
        Path packJsonPath = packDir.resolve("pack.json");
        if (!Files.isRegularFile(packJsonPath)) {
            System.err.println("pack directory has no pack.json: " + packDir);
            return 66;
        }

        ObjectNode report = MAPPER.createObjectNode();
        report.put("contractVersion", "npdev-pack-publish-artifact.v1");
        report.put("packDir", packDir.toString());

        try {
            // 1. Read and validate the pack
            JsonNode packJson = MAPPER.readTree(packJsonPath.toFile());
            report.put("pack", packJson.has("pack") ? packJson.get("pack").asText() : "unknown");
            report.put("version", packJson.has("version") ? packJson.get("version").asText() : "unknown");

            // 2. Compute the content digest (same algorithm as PackCache)
            String digest = sha256OfTree(packDir);
            report.put("digest", digest);

            // 3. Sign if keypair provided
            PackSignature signature = null;
            if (signArg != null) {
                Path keypairBase = Path.of(signArg);
                // Detect algorithm from the key files
                String algorithm = detectAlgorithm(keypairBase);
                KeyPair keyPair = PackSigner.readKeyPair(keypairBase, algorithm);
                signature = PackSigner.createSignature(algorithm, digest, keyPair);
                report.put("signed", true);
                report.put("signatureAlgorithm", algorithm);
            } else {
                report.put("signed", false);
            }

            if (dryRun) {
                report.put("dryRun", true);
                report.put("message", "Dry run: no artifacts written. Would produce OCI manifest + blob at "
                        + (outputArg != null ? outputArg : "<not specified>"));
                String json = serialize(report);
                if (outArg != null) {
                    writeReport(outArg, json);
                }
                System.out.println(json);
                return 0;
            }

            // 4. Write OCI artifact
            if (outputArg == null) {
                System.err.println("--output <dir> is required (or use --dry-run)");
                return 64;
            }
            Path outputDir = Path.of(outputArg);
            Files.createDirectories(outputDir);

            // Write the pack.json (with signature if provided) into a temp dir for zipping
            Path stagingDir = Files.createTempDirectory(outputDir, "pack-staging-");
            try {
                // Copy the pack tree to staging
                copyTree(packDir, stagingDir);

                // If signed, inject the signature into the staged pack.json
                if (signature != null) {
                    ObjectNode stagedPack = (ObjectNode) MAPPER.readTree(stagingDir.resolve("pack.json").toFile());
                    ObjectNode sigNode = stagedPack.putObject("signature");
                    sigNode.put("algorithm", signature.algorithm());
                    sigNode.put("digest", signature.digest());
                    sigNode.put("value", signature.value());
                    sigNode.put("publicKey", signature.publicKey());
                    Files.writeString(stagingDir.resolve("pack.json"),
                            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(stagedPack)
                                    + System.lineSeparator());
                }

                // Create the zip blob
                String packId = packJson.has("pack") ? packJson.get("pack").asText() : "pack";
                String version = packJson.has("version") ? packJson.get("version").asText() : "0.0.0";
                String blobName = packId + "-" + version + ".zip";
                byte[] zipBlob = createZipBlob(stagingDir);
                Path blobPath = outputDir.resolve(blobName);
                Files.write(blobPath, zipBlob);

                // Compute blob digest for the manifest
                String blobDigest = sha256OfBytes(zipBlob);

                // Write the OCI manifest
                ObjectNode manifest = MAPPER.createObjectNode();
                manifest.put("schemaVersion", 2);
                manifest.put("mediaType", "application/vnd.oci.image.manifest.v1+json");
                ObjectNode config = manifest.putObject("config");
                config.put("mediaType", "application/vnd.oci.image.config.v1+json");
                config.put("digest", "sha256:" + "0".repeat(64)); // empty config
                config.put("size", 0);
                ArrayNode layers = manifest.putArray("layers");
                ObjectNode layer = layers.addObject();
                layer.put("mediaType", "application/vnd.oci.image.layer.v1.tar+gzip");
                layer.put("digest", blobDigest);
                layer.put("size", zipBlob.length);
                ObjectNode annotations = layer.putObject("annotations");
                annotations.put("org.opencontainers.image.title", blobName);

                String manifestJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
                Path manifestPath = outputDir.resolve("manifest.json");
                Files.writeString(manifestPath, manifestJson + System.lineSeparator());

                report.put("outputDir", outputDir.toString());
                report.put("manifestPath", manifestPath.toString());
                report.put("blobPath", blobPath.toString());
                report.put("blobDigest", blobDigest);
                report.put("blobSize", zipBlob.length);
                report.put("message", "OCI artifact written to " + outputDir);

            } finally {
                deleteTree(stagingDir);
            }

            String json = serialize(report);
            if (outArg != null) {
                writeReport(outArg, json);
            }
            System.out.println(json);
            return 0;

        } catch (IOException | RuntimeException failure) {
            report.put("status", "failed");
            report.put("error", safeMessage(failure));
            String json = serialize(report);
            if (outArg != null) {
                writeReport(outArg, json);
            }
            System.out.println(json);
            System.err.println("publish failed: " + safeMessage(failure));
            return 2;
        } catch (Exception failure) {
            report.put("status", "failed");
            report.put("error", safeMessage(failure));
            String json = serialize(report);
            System.out.println(json);
            System.err.println("publish failed: " + safeMessage(failure));
            return 2;
        }
    }

    /**
     * Detects the algorithm from the keypair files. Tries Ed25519 first, falls back to RSA.
     */
    private static String detectAlgorithm(Path keypairBase) throws IOException {
        // Try reading as Ed25519 first
        try {
            PackSigner.readKeyPair(keypairBase, "Ed25519");
            return "Ed25519";
        } catch (Exception ignored) {
            // Not Ed25519, try RSA
        }
        try {
            PackSigner.readKeyPair(keypairBase, "SHA256withRSA");
            return "SHA256withRSA";
        } catch (Exception e) {
            throw new IOException("could not read keypair at " + keypairBase
                    + " as Ed25519 or SHA256withRSA: " + e.getMessage(), e);
        }
    }

    // ---- Shared helpers ------------------------------------------------------------------------

    /**
     * SHA-256 over every regular file under {@code root} (path-sorted, .git excluded) -- same
     * algorithm as {@code PackCache.sha256OfTree}.
     */
    static String sha256OfTree(Path root) throws IOException {
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(file -> !isUnderGitDirectory(root, file))
                    .sorted(Comparator.comparing(file -> normalizedRelative(root, file)))
                    .toList();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : files) {
                digest.update(normalizedRelative(root, file).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 not available", impossible);
        }
    }

    private static String sha256OfBytes(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 not available", impossible);
        }
    }

    private static byte[] createZipBlob(Path sourceDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (".git".equals(String.valueOf(dir.getFileName()))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(sourceDir)) {
                        String entryName = sourceDir.relativize(dir).toString().replace('\\', '/') + "/";
                        zos.putNextEntry(new ZipEntry(entryName));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return baos.toByteArray();
    }

    private static void copyTree(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (".git".equals(String.valueOf(dir.getFileName()))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path target = dest.resolve(source.relativize(dir));
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(source.relativize(file));
                Files.copy(file, target);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isUnderGitDirectory(Path root, Path file) {
        for (Path dir = file.getParent(); dir != null && !dir.equals(root); dir = dir.getParent()) {
            if (".git".equals(String.valueOf(dir.getFileName()))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedRelative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException e) {
            // best-effort cleanup
        }
    }

    // ---- Gate-mode helpers (unchanged from original) -------------------------------------------

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
