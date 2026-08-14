package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-5 steps 1+2+3+4: a REAL, LIVE proof of the git substrate -- an actual local git repository on
 * disk, cloned by the real {@code git} binary via {@code git+file://}, with zero network I/O. This
 * is the live-fetch proof the task's own scope explicitly recommends in place of touching a real
 * registry: {@code file://} exercises the exact same {@link RemotePackFetcher}/{@link PackCache}
 * code path a {@code git+https://} fetch would, the only difference being the transport git itself
 * uses underneath -- so this genuinely proves the fetch->digest->cache pipeline end to end, not just
 * the coordinate grammar (which {@code PackCoordinateTest} already covers in isolation).
 *
 * <p>Requires a real {@code git} binary on PATH -- already a hard dependency of this repository's
 * own tooling and CI (checkout itself needs it), so no new environment requirement is introduced.
 */
class RemotePackFetcherGitLiveTest {

    @TempDir
    Path work;

    @Test
    void fetchesAPackFromTheRepoRoot() throws Exception {
        Path repo = initRepo(work.resolve("repo-root"));
        Files.writeString(repo.resolve("pack.json"), "{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        commitAndTag(repo, "v2.1.0");

        PackCache cache = new PackCache(work.resolve("cache"));
        RemotePackFetcher.FetchResult result = RemotePackFetcher.fetch(
                PackCoordinate.parse(fileCoordinate(repo, "", "v2.1.0")), NetworkPolicy.ALLOWED, cache);

        assertEquals("{\"pack\":\"identity\",\"version\":\"2.1.0\"}", Files.readString(result.packJson()));
        assertTrue(cache.has(result.digestHex()));
    }

    @Test
    void fetchesAPackFromASubpathInsideAMonorepo() throws Exception {
        Path repo = initRepo(work.resolve("monorepo"));
        Files.createDirectories(repo.resolve("packs").resolve("billing"));
        Files.writeString(repo.resolve("packs").resolve("billing").resolve("pack.json"),
                "{\"pack\":\"billing\",\"version\":\"1.0.0\"}");
        // Also add a decoy file at the repo root so a wrong (root-not-subpath) resolution would be
        // caught immediately by a missing/incorrect pack.json.
        Files.writeString(repo.resolve("README.md"), "not a pack");
        commitAndTag(repo, "v1.0.0");

        PackCache cache = new PackCache(work.resolve("cache"));
        RemotePackFetcher.FetchResult result = RemotePackFetcher.fetch(
                PackCoordinate.parse(fileCoordinate(repo, "packs/billing", "v1.0.0")), NetworkPolicy.ALLOWED, cache);

        assertEquals("{\"pack\":\"billing\",\"version\":\"1.0.0\"}", Files.readString(result.packJson()));
    }

    @Test
    void refetchingTheSameTagIsByteIdenticalAndReusesTheSameCacheEntry() throws Exception {
        Path repo = initRepo(work.resolve("repo-repeat"));
        Files.writeString(repo.resolve("pack.json"), "{\"pack\":\"identity\",\"version\":\"3.0.0\"}");
        commitAndTag(repo, "v3.0.0");

        PackCache cache = new PackCache(work.resolve("cache"));
        String coordinate = fileCoordinate(repo, "", "v3.0.0");
        RemotePackFetcher.FetchResult first = RemotePackFetcher.fetch(PackCoordinate.parse(coordinate), NetworkPolicy.ALLOWED, cache);
        RemotePackFetcher.FetchResult second = RemotePackFetcher.fetch(PackCoordinate.parse(coordinate), NetworkPolicy.ALLOWED, cache);

        assertEquals(first.digestHex(), second.digestHex());
        assertEquals(Files.readString(first.packJson()), Files.readString(second.packJson()));
    }

    @Test
    void deniedPolicyRefusesBeforeAnyGitProcessRuns() throws Exception {
        Path repo = initRepo(work.resolve("repo-denied"));
        Files.writeString(repo.resolve("pack.json"), "{\"pack\":\"identity\",\"version\":\"1.0.0\"}");
        commitAndTag(repo, "v1.0.0");

        PackCache cache = new PackCache(work.resolve("cache"));
        PackCoordinate coordinate = PackCoordinate.parse(fileCoordinate(repo, "", "v1.0.0"));
        assertThrows(NetworkPolicyViolationException.class,
                () -> RemotePackFetcher.fetch(coordinate, NetworkPolicy.DENIED, cache));
    }

    @Test
    void fetchingATagThatDoesNotExistFailsCleanly() throws Exception {
        Path repo = initRepo(work.resolve("repo-badtag"));
        Files.writeString(repo.resolve("pack.json"), "{\"pack\":\"identity\",\"version\":\"1.0.0\"}");
        commitAndTag(repo, "v1.0.0");

        PackCache cache = new PackCache(work.resolve("cache"));
        PackCoordinate coordinate = PackCoordinate.parse(fileCoordinate(repo, "", "v9.9.9-does-not-exist"));
        IOException failure = assertThrows(IOException.class,
                () -> RemotePackFetcher.fetch(coordinate, NetworkPolicy.ALLOWED, cache));
        assertTrue(failure.getMessage().contains("git clone failed"), failure.getMessage());
    }

    private static String fileCoordinate(Path repo, String subpath, String tag) {
        String uri = repo.toUri().toString();
        if (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return "git+" + uri + (subpath.isEmpty() ? "" : "//" + subpath) + "@" + tag;
    }

    private static Path initRepo(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        run(dir, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com",
                "init", "--quiet", "--initial-branch=main");
        return dir;
    }

    private static void commitAndTag(Path repo, String tag) throws IOException, InterruptedException {
        run(repo, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com", "add", "-A");
        run(repo, "git", "-c", "user.name=npdev-test", "-c", "user.email=npdev-test@example.com",
                "commit", "--quiet", "-m", "pk5-test-fixture");
        run(repo, "git", "tag", tag);
    }

    private static void run(Path cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("command failed: " + List.of(command) + "\n" + output);
        }
    }
}
