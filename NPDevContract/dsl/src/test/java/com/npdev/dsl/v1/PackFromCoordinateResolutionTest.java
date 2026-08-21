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

    /**
     * R8.1: a fragment-structured pack is the NORMAL shape for anything beyond a toy pack, and it
     * used to die with a misleading "escapes the model root" error the moment it was fetched into
     * the cache -- {@code resolveJsonRefUnderRoot} required every file the pack's OWN fragments
     * pulled in to also live under the APP's model root, which a cache-resident pack never does by
     * construction. This is the exact repro PACK-8.yml and {@code PackCacheTest}'s digest-collision
     * test both point at as "not reachable through the real pipeline today".
     *
     * <p>Deliberately puts the app's model.json under a directory ({@code app/}) that is a SIBLING
     * of the redirected pack-cache root, not an ancestor of it -- every other test in this class
     * writes {@code model.json} directly into {@code temp}, which also happens to be the pack-cache
     * override's parent, so the cache entry is (accidentally, for those tests) still nested UNDER
     * the model root and the old app-root check would never have fired. The real default cache
     * root ({@code ~/.npdev/packs}) is never under an app's own directory, so this layout is the one
     * that actually reproduces the defect.
     */
    @Test
    void aFragmentStructuredRemotePackFetchesAndGeneratesFullyOfflineFromCache() throws Exception {
        Path appDir = Files.createDirectories(temp.resolve("app"));
        Path repo = initRepo(temp.resolve("catalog-repo"));
        Files.writeString(repo.resolve("pack.json"), """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Product", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ],
                  "fragments": [
                    { "$ref": "fragments/variant.json" }
                  ]
                }
                """);
        Files.createDirectories(repo.resolve("fragments"));
        Files.writeString(repo.resolve("fragments").resolve("variant.json"), """
                {
                  "concepts": [
                    { "name": "Variant", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);
        commitAndTag(repo, "v1.0.0");
        String from = fileCoordinate(repo, "v1.0.0");

        Path model = appDir.resolve("model.json");
        Files.writeString(model, """
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
        deleteRecursively(repo);

        ResolvedModelSource resolved = new ModelSourceResolver().resolve(model);
        assertTrue(containsConceptNamed(resolved.resolvedRoot(), "catalog::Product"),
                "the pack's own root concept must have been merged");
        assertTrue(containsConceptNamed(resolved.resolvedRoot(), "catalog::Variant"),
                "the fragment's concept must have been merged -- this is what used to throw "
                        + "\"escapes the model root\" for every cache-resident fragment");
    }

    /**
     * R8.1's RED control, kept alongside the fix above: a fragment that tries to escape ITS OWN
     * pack directory (not the app's model root -- the boundary this fix removed) must still refuse.
     * Removing the app-root check must not have widened the hole to "anything under the cache root".
     * Same sibling-directory layout as the test above, for the same reason.
     *
     * <p>The escaping {@code $ref} lives in a NESTED fragment (one reached from another fragment),
     * not in {@code pack.json} itself -- {@code pack.json}'s own {@code $ref} values are schema-
     * validated against a regex that already refuses a literal {@code ".."} segment (confirmed by
     * running this same escape directly in {@code pack.json}: it fails schema validation before
     * ever reaching {@code resolveJsonRefUnderRoot}). A fragment file is not re-validated against
     * that schema, so this is the shape that actually exercises the runtime containment check this
     * test is for.
     */
    @Test
    void aFragmentEscapingItsOwnPackDirectoryStillRefusesEvenWhenThePackIsCacheResident() throws Exception {
        Path appDir = Files.createDirectories(temp.resolve("app"));
        Path cacheRoot = temp.resolve("pack-cache");
        // The escape target: one level above ANY digest entry directory
        // (<cacheRoot>/sha256/<digest>/../outside.json resolves here), so it exists before the
        // fetch runs regardless of what digest this pack.json ends up hashing to.
        Files.createDirectories(cacheRoot.resolve("sha256"));
        Files.writeString(cacheRoot.resolve("sha256").resolve("outside.json"), "{}");

        Path repo = initRepo(temp.resolve("catalog-repo"));
        Files.writeString(repo.resolve("pack.json"), """
                {
                  "dslVersion": "1.0.0",
                  "pack": "catalog",
                  "version": "1.0.0",
                  "fragments": [
                    { "$ref": "level1.json" }
                  ]
                }
                """);
        Files.writeString(repo.resolve("level1.json"), """
                {
                  "fragments": [
                    { "$ref": "../outside.json" }
                  ]
                }
                """);
        commitAndTag(repo, "v1.0.0");
        String from = fileCoordinate(repo, "v1.0.0");

        Path model = appDir.resolve("model.json");
        Files.writeString(model, """
                {
                  "namespace": "pk5.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "from": "%s" } ]
                }
                """.formatted(from));

        // The escape is caught during resolution, which resolvePackGraphForCli (phase 1, "pack add")
        // already performs in full -- it never gets as far as writing a lock.
        IOException failure = assertThrows(IOException.class,
                () -> new ModelSourceResolver().resolvePackGraphForCli(model, NetworkPolicy.ALLOWED));
        assertTrue(failure.getMessage().contains("escapes the pack directory"), failure.getMessage());
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

    /**
     * PACK-8: a pack's OWN packs[] dependencies can use `from` to resolve transitively from a
     * remote coordinate, not just the app-level direct imports. This test creates:
     * - An "identity" pack repo (the transitive dependency)
     * - A "crm" pack repo that depends on identity via `from` (the intermediate pack)
     * - An app model that imports crm via `from`
     * The identity pack is resolved transitively through crm's `from` declaration.
     */
    @Test
    void transitivePackFromResolvesThroughCacheOffline() throws Exception {
        // The transitive dependency: identity pack
        Path identityRepo = initRepo(temp.resolve("identity-repo"));
        Files.writeString(identityRepo.resolve("pack.json"), """
                {
                  "dslVersion": "1.0.0",
                  "pack": "identity",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "User", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);
        commitAndTag(identityRepo, "v1.0.0");
        String identityFrom = fileCoordinate(identityRepo, "v1.0.0");

        // The intermediate pack: crm, which depends on identity via `from`
        Path crmRepo = initRepo(temp.resolve("crm-repo"));
        Files.writeString(crmRepo.resolve("pack.json"), """
                {
                  "dslVersion": "1.0.0",
                  "pack": "crm",
                  "version": "1.0.0",
                  "packs": [
                    { "pack": "identity", "version": "1.0.0", "from": "%s" }
                  ],
                  "concepts": [
                    { "name": "Account", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """.formatted(identityFrom));
        commitAndTag(crmRepo, "v1.0.0");
        String crmFrom = fileCoordinate(crmRepo, "v1.0.0");

        // The app model: imports crm via `from`
        Path model = write("model.json", """
                {
                  "namespace": "pk8.transitive.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "from": "%s" } ]
                }
                """.formatted(crmFrom));

        // Phase 1: "npdev pack add" -- fetches crm (and transitively identity) into cache
        ModelSourceResolver.PackCliResolution resolution =
                new ModelSourceResolver().resolvePackGraphForCli(model, NetworkPolicy.ALLOWED);
        assertTrue(resolution.lockEntries().containsKey("crm"), "crm should be in the lock");
        assertTrue(resolution.lockEntries().containsKey("identity"), "identity should be in the lock (resolved transitively)");
        PackLockFile.of(resolution.lockEntries()).write(resolution.rootDirectory());

        // Delete BOTH source repos -- if the generate path tries to re-fetch, it fails
        deleteRecursively(identityRepo);
        deleteRecursively(crmRepo);

        // Phase 2: "npdev generate" -- resolves entirely from cache
        ResolvedModelSource resolved = new ModelSourceResolver().resolve(model);
        assertTrue(containsConceptNamed(resolved.resolvedRoot(), "crm::Account"),
                "the intermediate pack's concept must be merged");
        assertTrue(containsConceptNamed(resolved.resolvedRoot(), "identity::User"),
                "the transitively-resolved pack's concept must be merged via transitive `from`");
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
