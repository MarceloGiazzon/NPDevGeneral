package com.npdev.dsl.v1.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
