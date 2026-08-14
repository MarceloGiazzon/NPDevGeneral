package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.pack.NetworkPolicy;
import com.npdev.dsl.v1.pack.PackCache;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-5 ("Distribution", {@code PACK-ROADMAP.md} card PK-5): the FULL two-phase pipeline, end to
 * end, through the same two public entry points the CLI/real generate path actually use --
 * {@code ModelSourceResolver.resolvePackGraphForCli} (what {@code npdev pack add} calls, network-
 * ALLOWED) and {@code ModelSourceResolver.resolve} (what {@code npdev generate}/{@code validate}
 * call, network-DENIED, hardcoded in {@code PackDependencyGraphWalker}).
 *
 * <p>Uses a real local git repository (see {@code RemotePackFetcherGitLiveTest} for why {@code
 * git+file://} is a legitimate live substrate proof) and redirects the process-wide pack cache into
 * a per-test temp directory via {@link PackCache#PROPERTY_ROOT_OVERRIDE} -- the one call site that
 * uses {@link PackCache#atDefaultRoot()} ({@code PackDependencyGraphWalker.resolveRemotePackFile})
 * would otherwise write into this machine's real {@code ~/.npdev/packs}.
 */
class PackFromCoordinateResolutionTest {

    @TempDir
    Path temp;

    @BeforeEach
    void redirectCacheIntoTempDir() {
        System.setProperty(PackCache.PROPERTY_ROOT_OVERRIDE, temp.resolve("pack-cache").toString());
    }

    @AfterEach
    void clearCacheOverride() {
        System.clearProperty(PackCache.PROPERTY_ROOT_OVERRIDE);
    }

    @Test
    void addFetchesAndLocksThenGenerateResolvesFromCacheOnlyEvenAfterTheSourceRepoIsDeleted() throws Exception {
        Path repo = initRepo(temp.resolve("identity-repo"));
        Files.writeString(repo.resolve("pack.json"), """
                {
                  "dslVersion": "1.0.0",
                  "pack": "identity",
                  "version": "2.1.0",
                  "concepts": [
                    { "name": "Item", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);
        commitAndTag(repo, "v2.1.0");
        String from = fileCoordinate(repo, "v2.1.0");

        Path model = write("model.json", """
                {
                  "namespace": "pk5.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "from": "%s" } ]
                }
                """.formatted(from));

        // Phase 1: "npdev pack add" -- network ALLOWED, this is the one phase that may fetch.
        ModelSourceResolver.PackCliResolution resolution =
                new ModelSourceResolver().resolvePackGraphForCli(model, NetworkPolicy.ALLOWED);
        assertEquals(1, resolution.lockEntries().size());
        PackLockFile.LockedPack locked = resolution.lockEntries().get("identity");
        assertEquals(from, locked.from());
        PackLockFile.of(resolution.lockEntries()).write(resolution.rootDirectory());

        // The source repo is now gone from disk entirely -- if the generate path below tried to
        // re-fetch (re-clone), it would fail immediately since there is nothing left to clone.
        deleteRecursively(repo);

        // Phase 2: "npdev generate" -- network DENIED (hardcoded), reads ONLY the lock + cache.
        ResolvedModelSource resolved = new ModelSourceResolver().resolve(model);
        assertTrue(containsConceptNamed(resolved.resolvedRoot(), "identity::Item"),
                "the remote pack's concept must have been merged into the resolved model");

        // Byte-deterministic: resolving twice from the same lock+cache produces identical output.
        ResolvedModelSource resolvedAgain = new ModelSourceResolver().resolve(model);
        assertEquals(resolved.resolvedRoot().toString(), resolvedAgain.resolvedRoot().toString());
    }

    @Test
    void generateRefusesWithNoLockAtAllNamingPackAdd() throws Exception {
        Path repo = initRepo(temp.resolve("identity-repo"));
        Files.writeString(repo.resolve("pack.json"), minimalPackJson());
        commitAndTag(repo, "v1.0.0");
        String from = fileCoordinate(repo, "v1.0.0");

        Path model = write("model.json", """
                {
                  "namespace": "pk5.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "from": "%s" } ]
                }
                """.formatted(from));

        IOException failure = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(failure.getMessage().contains("npdev pack add"), failure.getMessage());
    }

    @Test
    void generateRefusesWhenTheLockHasNoMatchingFromEntry() throws Exception {
        Path repo = initRepo(temp.resolve("identity-repo"));
        Files.writeString(repo.resolve("pack.json"), minimalPackJson());
        commitAndTag(repo, "v1.0.0");
        String from = fileCoordinate(repo, "v1.0.0");

        Path model = write("model.json", """
                {
                  "namespace": "pk5.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "from": "%s" } ]
                }
                """.formatted(from));

        // A lock exists, but for a completely different coordinate (e.g. a stale tag left over
        // from before the model.json's own `from` was bumped).
        PackLockFile.of(java.util.Map.of("identity", new PackLockFile.LockedPack(
                "1.0.0", "sha256:" + "0".repeat(64), "irrelevant", "", from + "-stale")))
                .write(temp);

        IOException failure = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(failure.getMessage().contains("is not in"), failure.getMessage());
    }

    @Test
    void generateHardRefusesWhenTheCacheEntryIsCorrupted() throws Exception {
        Path repo = initRepo(temp.resolve("identity-repo"));
        Files.writeString(repo.resolve("pack.json"), minimalPackJson());
        commitAndTag(repo, "v1.0.0");
        String from = fileCoordinate(repo, "v1.0.0");

        Path model = write("model.json", """
                {
                  "namespace": "pk5.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "from": "%s" } ]
                }
                """.formatted(from));

        ModelSourceResolver.PackCliResolution resolution =
                new ModelSourceResolver().resolvePackGraphForCli(model, NetworkPolicy.ALLOWED);
        PackLockFile.of(resolution.lockEntries()).write(resolution.rootDirectory());
        String digest = resolution.lockEntries().get("identity").digest();
        String digestHex = digest.substring("sha256:".length());

        PackCache cache = new PackCache(Path.of(System.getProperty(PackCache.PROPERTY_ROOT_OVERRIDE)));
        Files.writeString(cache.entryDir(digestHex).resolve("pack.json"), "{\"pack\":\"TAMPERED\"}");

        IOException failure = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
        assertTrue(failure.getMessage().contains("CORRUPT"), failure.getMessage());
    }

    private static boolean containsConceptNamed(JsonNode resolvedRoot, String name) {
        JsonNode concepts = resolvedRoot.get("concepts");
        if (concepts == null) {
            return false;
        }
        for (JsonNode concept : concepts) {
            if (name.equals(concept.path("name").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private static String minimalPackJson() {
        return """
                {
                  "dslVersion": "1.0.0",
                  "pack": "identity",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Item", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """;
    }

    private static String fileCoordinate(Path repo, String tag) {
        String uri = repo.toUri().toString();
        if (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return "git+" + uri + "@" + tag;
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

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    private Path write(String relative, String content) throws IOException {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
