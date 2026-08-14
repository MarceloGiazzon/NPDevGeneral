package com.npdev.dsl.v1.pack;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
 * <p><b>OCI substrate: coordinate parsing only, fetch deliberately NOT implemented</b> -- pulling a
 * real OCI artifact needs a Distribution-API v2 HTTP client (manifest fetch, auth-token dance, blob
 * fetch) that can only be verified against a real registry, and this session's scope explicitly
 * forbids touching one (ghcr.io or otherwise). Writing that client with zero live verification would
 * be exactly the kind of unproven code this repository's own culture (falsifiable-DONE guard fields,
 * "a green RED-proof meant the TEST was wrong") argues against. The guard check below still runs
 * FIRST for an OCI coordinate too, so {@link NetworkPolicy#DENIED} refuses an OCI fetch attempt
 * exactly as it refuses a git one -- only the "then actually do it" half is deferred. See
 * {@code ledger/items/PACK-8.yml} for the follow-up.
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
            // IOException (not UnsupportedOperationException, post-review fix, PR #70 review
            // finding, low severity but cheap): PackAddMain/PackUpdateMain's `catch (IOException
            // failure)` is the ONLY place any pack-resolution failure gets turned into this CLI's
            // documented JSON {"status":"failed","error":...} + exit 2 contract -- an unchecked
            // exception type here skipped that entirely and crashed with a raw, uncaught stack
            // trace instead, for the one coordinate scheme guaranteed to hit this path today.
            throw new IOException("OCI registry fetch (" + oci
                    + ") is not implemented in this slice (PK-5 steps 1/2/4 shipped: local cache, "
                    + "network-policy guard, digest verification, and coordinate parsing for both "
                    + "schemes; the git substrate's fetch is fully implemented and live-tested). "
                    + "Pulling a real OCI artifact requires a Distribution-API v2 client verified "
                    + "against a real registry, out of this session's scope -- see "
                    + "ledger/items/PACK-8.yml for the follow-up.");
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
}
