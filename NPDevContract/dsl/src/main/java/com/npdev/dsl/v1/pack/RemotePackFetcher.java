package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * PK-5 steps 1+2+4 ("Distribution", {@code PACK-ROADMAP.md} card PK-5): fetches a {@link
 * PackCoordinate} into the local content-addressed {@link PackCache}. The ONLY call site allowed to
 * invoke {@link #fetch} with {@link NetworkPolicy#ALLOWED} is {@code PackAddMain}/{@code
 * PackUpdateMain} (via {@code PackDependencyGraphWalker}'s CLI entry point) -- the real generate/
 * validate path never calls this class at all, which is the stronger of the two guarantees the card
 * asks for ("a future edit cannot quietly reintroduce a fetch"); {@link NetworkPolicy#requireAllowed}
 * is the mechanical backstop for the weaker case (some future caller passing the wrong policy).
 *
 * <p><b>Git substrate: fully implemented, real, and live-verified</b> ({@code
 * RemotePackFetcherGitLiveTest}) -- shells out to the system {@code git} binary
 * ({@code git clone --branch <tag> --depth 1 <url> <dest>}), which is already a hard runtime
 * dependency of this repository's own tooling. {@code file://} is a first-class transport (not a
 * test-only shim) precisely because it lets the whole two-phase fetch->cache->offline-read pipeline
 * be proven end to end with a real {@code git clone} and zero network I/O.
 *
 * <p><b>OCI substrate: fully implemented</b> -- uses a minimal OCI Distribution API v2 HTTP client
 * ({@link OciDistributionClient}) built on {@code java.net.http.HttpClient}, with Bearer token
 * authentication (the realm/service/scope challenge-response dance). The blob format is a zip file
 * containing the pack tree. Verified end-to-end against a test-only fake registry
 * ({@code FakeOciRegistry}) that exercises the full fetch->cache->offline-read pipeline.
 */
public final class RemotePackFetcher {

    private RemotePackFetcher() {
    }

    public record FetchResult(String digestHex, Path packJson) {
    }

    public static FetchResult fetch(PackCoordinate coordinate, NetworkPolicy policy, PackCache cache) throws IOException {
        policy.requireAllowed("fetch pack from " + describe(coordinate));

        if (coordinate instanceof GitCoordinate git) {
            return fetchGit(git, cache);
        }
        if (coordinate instanceof OciCoordinate oci) {
            return fetchOci(oci, cache);
        }
        throw new IllegalStateException("unreachable: unknown PackCoordinate implementation " + coordinate.getClass());
    }

    private static String describe(PackCoordinate coordinate) {
        return coordinate.raw();
    }

    private static FetchResult fetchGit(GitCoordinate git, PackCache cache) throws IOException {
        Path tempClone = Files.createTempDirectory("npdev-pack-fetch-");
        try {
            String cloneSource = "file".equals(git.transport()) ? filePathFor(git) : git.fullUrl();
            runGitClone(cloneSource, git.tag(), tempClone);

            Path packSourceDir = git.subpath().isEmpty() ? tempClone : tempClone.resolve(git.subpath());
            if (!Files.isDirectory(packSourceDir)) {
                throw new IOException("git coordinate's subpath does not exist after clone: '" + git.subpath()
                        + "' under " + git.fullUrl() + "@" + git.tag());
            }
            if (!Files.isRegularFile(packSourceDir.resolve("pack.json"))) {
                throw new IOException("git coordinate resolves to a directory with no pack.json: " + packSourceDir);
            }

            String digestHex = cache.store(packSourceDir);
            Path verifiedPackJson = cache.read(digestHex);
            return new FetchResult(digestHex, verifiedPackJson);
        } finally {
            PackCache.deleteTree(tempClone);
        }
    }

    /** {@code file://} URLs (including the Windows-drive-letter shape {@code file:///D:/x/y}) round
     *  trip through {@link URI}/{@link Path} more reliably than manual string surgery on the leading
     *  slash a Windows path picks up after {@code file://}. */
    private static String filePathFor(GitCoordinate git) throws IOException {
        try {
            return Path.of(new URI(git.fullUrl())).toString();
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            throw new IOException("git+file:// coordinate is not a valid local path: " + git.fullUrl(), malformed);
        }
    }

    private static void runGitClone(String source, String tag, Path dest) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                "git", "clone", "--quiet", "--branch", tag, "--depth", "1", source, dest.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException cannotStart) {
            throw new IOException("could not start 'git': " + cannotStart.getMessage(), cannotStart);
        }
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("git clone timed out after 120s cloning " + source + "@" + tag);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("git clone was interrupted: " + source + "@" + tag, interrupted);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git clone failed (exit " + process.exitValue() + ") for " + source + "@" + tag
                    + ": " + output.trim());
        }
    }

    private static FetchResult fetchOci(OciCoordinate oci, PackCache cache) throws IOException {
        String registryBaseUrl = "http://" + oci.registry();
        OciDistributionClient client = new OciDistributionClient();

        String manifestJson = client.fetchManifest(registryBaseUrl, oci.repository(), oci.reference());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(manifestJson);
        JsonNode layers = manifest.get("layers");
        if (layers == null || !layers.isArray() || layers.isEmpty()) {
            throw new IOException("OCI manifest has no layers: " + oci);
        }
        String layerDigest = layers.get(0).get("digest").asText();

        byte[] blob = client.fetchBlob(registryBaseUrl, oci.repository(), layerDigest);

        Path tempExtract = Files.createTempDirectory("npdev-pack-oci-");
        try {
            extractZip(blob, tempExtract);

            Path packJson = tempExtract.resolve("pack.json");
            if (!Files.isRegularFile(packJson)) {
                throw new IOException("OCI blob does not contain a pack.json at its root: " + oci);
            }

            String digestHex = cache.store(tempExtract);
            Path verifiedPackJson = cache.read(digestHex);
            return new FetchResult(digestHex, verifiedPackJson);
        } finally {
            PackCache.deleteTree(tempExtract);
        }
    }

    private static void extractZip(byte[] zipData, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(destDir)) {
                    throw new IOException("zip entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.copy(zis, outPath);
                }
                zis.closeEntry();
            }
        }
    }
}
