package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** PK-5 steps 1+4: the content-addressed local cache -- store/read round trip, and the "corrupt a
 *  cache entry -> hard refusal" proof the card's own Proof section names explicitly. */
class PackCacheTest {

    @TempDir
    Path tempCacheRoot;

    private PackCache cache() {
        return new PackCache(tempCacheRoot);
    }

    private Path fetchedTree(String packJsonContent) throws IOException {
        Path tree = Files.createTempDirectory(tempCacheRoot.getParent(), "fetched-");
        Files.writeString(tree.resolve("pack.json"), packJsonContent);
        return tree;
    }

    /** Writes a minimal {@code pack.json} for {@code id} under {@code dir}, creating it if needed. */
    private void writePack(Path dir, String id, String extraContent) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pack.json"),
                "{\"pack\":\"" + id + "\",\"version\":\"1.0.0\"" + (extraContent == null ? "" : "," + extraContent) + "}");
    }

    @Test
    void storeThenReadRoundTrips() throws Exception {
        Path tree = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        String digest = cache().store(tree);

        assertTrue(cache().has(digest));
        Path packJson = cache().read(digest);
        assertEquals("{\"pack\":\"identity\",\"version\":\"2.1.0\"}", Files.readString(packJson));
    }

    @Test
    void entryDirIsKeyedByTheDigestUnderSha256Segment() throws Exception {
        Path tree = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        String digest = cache().store(tree);
        assertEquals(tempCacheRoot.resolve("sha256").resolve(digest), cache().entryDir(digest));
    }

    @Test
    void missingEntryRefusesOnRead() {
        IOException failure = assertThrows(IOException.class, () -> cache().read("f".repeat(64)));
        assertTrue(failure.getMessage().contains("missing"), failure.getMessage());
    }

    @Test
    void corruptedEntryHardRefusesOnRead() throws Exception {
        Path tree = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        String digest = cache().store(tree);

        // Simulate corruption/tampering directly on disk, after the fact.
        Files.writeString(cache().entryDir(digest).resolve("pack.json"), "{\"pack\":\"TAMPERED\"}");

        IOException failure = assertThrows(IOException.class, () -> cache().read(digest));
        assertTrue(failure.getMessage().contains("CORRUPT"), failure.getMessage());
    }

    @Test
    void reStoringIdenticalContentIsANoOp() throws Exception {
        Path tree1 = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        Path tree2 = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        String digest1 = cache().store(tree1);
        String digest2 = cache().store(tree2);
        assertEquals(digest1, digest2);
    }

    @Test
    void differentContentProducesDifferentDigests() throws Exception {
        Path tree1 = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        Path tree2 = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.2.0\"}");
        assertFalse(cache().store(tree1).equals(cache().store(tree2)));
    }

    @Test
    void storeCopiesSiblingFragmentFilesToo() throws Exception {
        Path tree = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        Files.writeString(tree.resolve("roles-fragment.json"), "{\"roles\":[]}");
        String digest = cache().store(tree);
        Path packJson = cache().read(digest);
        assertTrue(Files.isRegularFile(packJson.getParent().resolve("roles-fragment.json")));
    }

    /**
     * PK-5 regression (adversarial multi-agent review of PR #70, finding #2, blocker severity): the
     * digest used to cover ONLY {@code pack.json}, so two fetched trees with byte-identical
     * {@code pack.json} but DIFFERENT fragment content silently aliased to the SAME cache entry --
     * the second {@code store()} became a no-op (its fragment content was simply discarded), and
     * {@code read()} would report the surviving entry as digest-verified while permanently serving
     * the FIRST tree's fragments. Was not reachable through the real pipeline when this test was
     * written (a separate "escapes the model root" bug blocked any remote+fragment pack from
     * resolving at all -- see PACK-8.yml / R8.1, fixed in {@code ModelSourceResolver}'s
     * {@code resolveJsonRefUnderRoot}), but a landmine in a brand-new shared, machine-wide cache
     * primitive whose own class doc claims "a same-digest entry can never legitimately differ" --
     * and now that R8.1 is fixed, IS reachable, so this coverage was load-bearing rather than
     * defensive. See {@code PackFromCoordinateResolutionTest} for the fragment-resolution proof.
     */
    @Test
    void identicalPackJsonWithDifferentFragmentContentProducesDifferentDigests() throws Exception {
        Path tree1 = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        Files.writeString(tree1.resolve("roles-fragment.json"), "{\"roles\":[\"admin\"]}");

        Path tree2 = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        Files.writeString(tree2.resolve("roles-fragment.json"), "{\"roles\":[\"admin\",\"viewer\"]}");

        String digest1 = cache().store(tree1);
        String digest2 = cache().store(tree2);

        assertFalse(digest1.equals(digest2),
                "identical pack.json + different fragment content must NOT alias to the same cache digest");
        // Both entries must independently survive: storing tree2 must not have silently no-op'd or
        // clobbered tree1's own already-cached fragment content.
        assertEquals("{\"roles\":[\"admin\"]}",
                Files.readString(cache().read(digest1).getParent().resolve("roles-fragment.json")));
        assertEquals("{\"roles\":[\"admin\",\"viewer\"]}",
                Files.readString(cache().read(digest2).getParent().resolve("roles-fragment.json")));
    }

    @Test
    void identicalWholeTreeIncludingFragmentsReusesTheSameCacheEntry() throws Exception {
        Path tree1 = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        Files.writeString(tree1.resolve("roles-fragment.json"), "{\"roles\":[\"admin\"]}");

        Path tree2 = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        Files.writeString(tree2.resolve("roles-fragment.json"), "{\"roles\":[\"admin\"]}");

        assertEquals(cache().store(tree1), cache().store(tree2));
    }

    @Test
    void storeExcludesGitMetadataDirectory() throws Exception {
        Path tree = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        Files.createDirectories(tree.resolve(".git").resolve("objects"));
        Files.writeString(tree.resolve(".git").resolve("HEAD"), "ref: refs/heads/main");
        String digest = cache().store(tree);
        assertFalse(Files.exists(cache().entryDir(digest).resolve(".git")));
    }

    @Test
    void storeRejectsATreeWithNoPackJson() throws Exception {
        Path tree = Files.createTempDirectory(tempCacheRoot.getParent(), "no-pack-json-");
        assertThrows(IOException.class, () -> cache().store(tree));
    }

    @Test
    void defaultRootIsUnderTheUserHomeDirectoryByDefault() {
        // NPDEV_PACK_CACHE_ROOT isn't set in this test process (JVM env vars can't be mutated
        // portably from a test, so the override branch is exercised indirectly -- every other test
        // in this class sets it via the harness/CI environment when NPDEV_PACK_CACHE_ROOT IS
        // present, which redirects PackAddMain/PackDependencyGraphWalker's own defaultRoot() calls
        // during the live integration tests; see RemotePackFetcherGitLiveTest). Here, with no
        // override, the real default must be the well-known ~/.npdev/packs location, never
        // something that could accidentally resolve inside a temp/test directory.
        Path root = PackCache.defaultRoot();
        if (System.getenv(PackCache.ENV_ROOT_OVERRIDE) == null
                && System.getProperty(PackCache.PROPERTY_ROOT_OVERRIDE) == null) {
            assertEquals(Path.of(System.getProperty("user.home"), ".npdev", "packs"), root);
        }
    }

    @Test
    void configuredMirrorsParsesPropertySplitTrimmedAndSkipsBlanks() {
        String previous = System.getProperty(PackCache.PROPERTY_MIRRORS);
        try {
            System.setProperty(PackCache.PROPERTY_MIRRORS, " a" + File.pathSeparator + File.pathSeparator + "b ");
            assertEquals(List.of(Path.of("a"), Path.of("b")), PackCache.configuredMirrors());
        } finally {
            if (previous == null) {
                System.clearProperty(PackCache.PROPERTY_MIRRORS);
            } else {
                System.setProperty(PackCache.PROPERTY_MIRRORS, previous);
            }
        }
    }

    @Test
    void configuredMirrorsIsEmptyWhenPropertyBlankAndEnvUnset() {
        assumeTrue(System.getenv(PackCache.ENV_MIRRORS) == null);
        String previous = System.getProperty(PackCache.PROPERTY_MIRRORS);
        try {
            System.setProperty(PackCache.PROPERTY_MIRRORS, "  ");
            assertEquals(List.of(), PackCache.configuredMirrors());
        } finally {
            if (previous == null) {
                System.clearProperty(PackCache.PROPERTY_MIRRORS);
            } else {
                System.setProperty(PackCache.PROPERTY_MIRRORS, previous);
            }
        }
    }

    @Test
    void lockSourcePathIsCacheRelative() {
        assertEquals("sha256/abc/pack.json", PackCache.lockSourcePath("abc"));
    }

    @Test
    void resolveLockSourcePathResolvesRelativeUnderRoot() {
        assertEquals(tempCacheRoot.resolve("sha256/x/pack.json"),
                cache().resolveLockSourcePath("sha256/x/pack.json"));
    }

    @Test
    void resolveLockSourcePathKeepsAbsoluteUnchanged() throws IOException {
        Path absolute = Files.createTempDirectory(tempCacheRoot.getParent(), "absolute-").resolve("pack.json");
        assertEquals(absolute, cache().resolveLockSourcePath(absolute.toString()));
    }

    @Test
    void readOrSeedReturnsCacheHitWithoutTouchingMirrors() throws Exception {
        Path tree = fetchedTree("{\"pack\":\"identity\",\"version\":\"2.1.0\"}");
        String hex = cache().store(tree);

        Path nonExistentMirror = tempCacheRoot.getParent().resolve("does-not-exist-mirror");
        Path result = cache().readOrSeedFromMirrors(hex, "identity", List.of(nonExistentMirror));

        assertEquals(cache().read(hex), result);
    }

    @Test
    void readOrSeedSeedsFromMatchingMirror() throws Exception {
        String packId = "widgets";
        Path mirrorRoot = Files.createTempDirectory(tempCacheRoot.getParent(), "mirror-root-");
        Path packDirInMirror = mirrorRoot.resolve(packId);
        writePack(packDirInMirror, packId, null);

        Path throwawayRoot = Files.createTempDirectory(tempCacheRoot.getParent(), "throwaway-cache-");
        String hex = new PackCache(throwawayRoot).store(packDirInMirror);

        assertFalse(cache().has(hex));
        Path result = cache().readOrSeedFromMirrors(hex, packId, List.of(mirrorRoot));
        assertTrue(Files.isRegularFile(result));
        assertTrue(cache().has(hex));
    }

    @Test
    void readOrSeedIgnoresMirrorWithDifferentContent() throws Exception {
        String packId = "widgets";
        Path mirrorRoot = Files.createTempDirectory(tempCacheRoot.getParent(), "mirror-root-");
        writePack(mirrorRoot.resolve(packId), packId, null);

        String hex = "f".repeat(64);
        assertThrows(IOException.class, () -> cache().readOrSeedFromMirrors(hex, packId, List.of(mirrorRoot)));
        assertFalse(cache().has(hex));
    }

    @Test
    void readOrSeedNeverConsultsMirrorsForInvalidPackId() throws Exception {
        Path mirrorRoot = Files.createTempDirectory(tempCacheRoot.getParent(), "mirror-root-");
        Path packDirInMirror = mirrorRoot.resolve("x");
        writePack(packDirInMirror, "x", null);

        Path throwawayRoot = Files.createTempDirectory(tempCacheRoot.getParent(), "throwaway-cache-");
        String hex = new PackCache(throwawayRoot).store(packDirInMirror);

        assertThrows(IOException.class, () -> cache().readOrSeedFromMirrors(hex, "../x", List.of(mirrorRoot)));
        assertFalse(cache().has(hex));

        assertThrows(IOException.class, () -> cache().readOrSeedFromMirrors(hex, null, List.of(mirrorRoot)));
        assertFalse(cache().has(hex));
    }

    @Test
    void readOrSeedSkipsMirrorWithoutPackJson() throws Exception {
        String packId = "widgets";
        Path mirror1 = Files.createTempDirectory(tempCacheRoot.getParent(), "mirror1-");
        Files.createDirectories(mirror1.resolve(packId)); // no pack.json inside

        Path mirror2 = Files.createTempDirectory(tempCacheRoot.getParent(), "mirror2-");
        Path packDir2 = mirror2.resolve(packId);
        writePack(packDir2, packId, null);

        Path throwawayRoot = Files.createTempDirectory(tempCacheRoot.getParent(), "throwaway-cache-");
        String hex = new PackCache(throwawayRoot).store(packDir2);

        Path result = cache().readOrSeedFromMirrors(hex, packId, List.of(mirror1, mirror2));
        assertTrue(Files.isRegularFile(result));
        assertTrue(cache().has(hex));
    }

    @Test
    void readOrSeedUsesFirstMatchingMirror() throws Exception {
        String packId = "widgets";
        Path mirror1 = Files.createTempDirectory(tempCacheRoot.getParent(), "mirror1-");
        Path packDir1 = mirror1.resolve(packId);
        writePack(packDir1, packId, null);

        Path mirror2 = Files.createTempDirectory(tempCacheRoot.getParent(), "mirror2-");
        Path packDir2 = mirror2.resolve(packId);
        writePack(packDir2, packId, null);

        Path throwawayRoot = Files.createTempDirectory(tempCacheRoot.getParent(), "throwaway-cache-");
        String hex = new PackCache(throwawayRoot).store(packDir1);

        Path result = cache().readOrSeedFromMirrors(hex, packId, List.of(mirror1, mirror2));
        assertTrue(Files.isRegularFile(result));
        assertTrue(cache().has(hex));
    }
}
