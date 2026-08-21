package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.RuntimeMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R1.7 (roadmap Wave 1, "hot metadata swap"): proves {@link RuntimeMetadataService#applyMetadataOnlyReload}
 * actually makes a metadata-only change visible through the SAME read methods a running app's
 * controllers call ({@link RuntimeMetadataService#overview()}/{@link RuntimeMetadataService#catalog})
 * -- without restarting anything, since this is a plain instance method call against a live service --
 * and that it refuses anything that is not {@code METADATA_ONLY} without touching a single file.
 */
class RuntimeMetadataServiceHotSwapTest {

    @TempDir
    Path appExternalRoot;

    private RuntimeMetadataService service;
    private Path externalCompiledMetadata;
    private Path externalIndex;
    private Path externalConceptsManifest;

    @BeforeEach
    void setUp() throws IOException {
        Path generatedResourcesRoot = appExternalRoot.resolve("npdev-generated/src/main/resources");
        externalCompiledMetadata = generatedResourcesRoot.resolve("npdev/compiled-metadata.json");
        externalIndex = generatedResourcesRoot.resolve("npdev/metadata/index.json");
        externalConceptsManifest = generatedResourcesRoot.resolve("npdev/metadata/concepts.manifest.json");

        writeFixture(externalCompiledMetadata, compiledMetadataJson("old-namespace"));
        writeFixture(externalIndex, indexJson());
        writeFixture(externalConceptsManifest, conceptsManifestJson("Old Label"));

        service = new RuntimeMetadataService(
                new ObjectMapper(),
                externalCompiledMetadata.toString(),
                externalIndex.toString(),
                generatedResourcesRoot.toString());
    }

    @Test
    void reflectsOldContentBeforeAnyReload() {
        assertEquals("old-namespace", service.overview().get("namespace"));
        assertEquals("Old Label", firstConceptLabel());
        assertEquals(0L, service.reloadStatus().get("metadataGeneration"));
    }

    @Test
    void appliesAMetadataOnlyReloadWithoutAnyRestart() throws IOException {
        Path sourceRoot = stageNewMetadata("new-namespace", "New Label");

        RuntimeMetadataService.MetadataReloadResult result =
                service.applyMetadataOnlyReload("METADATA_ONLY", List.of("no schema-shaped change"), sourceRoot);

        assertEquals(1L, result.generation());
        assertEquals(3, result.catalogsUpdated().size());

        // The SAME service instance, no restart, no new object -- overview()/catalog() now see the
        // new content because they re-read from disk under the read lock.
        assertEquals("new-namespace", service.overview().get("namespace"));
        assertEquals("New Label", firstConceptLabel());
        assertEquals(1L, service.reloadStatus().get("metadataGeneration"));
    }

    @Test
    void refusesAndTouchesNothingWhenClassificationIsNotMetadataOnly() throws IOException {
        Path sourceRoot = stageNewMetadata("attempted-namespace", "Attempted Label");

        RuntimeMetadataService.MetadataChangeRefusedException refusal = assertThrows(
                RuntimeMetadataService.MetadataChangeRefusedException.class,
                () -> service.applyMetadataOnlyReload(
                        "SAFE_ADDITIVE", List.of("[SAFE_ADDITIVE] added table Foo"), sourceRoot));
        assertEquals("SAFE_ADDITIVE", refusal.classification());

        // Refused before touching a single file: still the OLD content, generation unchanged.
        assertEquals("old-namespace", service.overview().get("namespace"));
        assertEquals("Old Label", firstConceptLabel());
        assertEquals(0L, service.reloadStatus().get("metadataGeneration"));
    }

    @Test
    void refusesOnAMalformedSourceDirectoryWithoutTouchingAnyDestinationFile() throws IOException {
        Path sourceRoot = stageNewMetadata("broken-namespace", "Broken Label");
        // Corrupt the concepts manifest the new index still references -- pre-flight validation must
        // catch this BEFORE writing compiled-metadata.json or index.json, even though those two parse
        // fine on their own.
        Path brokenManifest = sourceRoot.resolve("src/main/resources/npdev/metadata/concepts.manifest.json");
        Files.writeString(brokenManifest, "{ not valid json");

        assertThrows(IllegalArgumentException.class, () -> service.applyMetadataOnlyReload(
                "METADATA_ONLY", List.of("no schema-shaped change"), sourceRoot));

        assertEquals("old-namespace", service.overview().get("namespace"));
        assertEquals(0L, service.reloadStatus().get("metadataGeneration"));
    }

    @Test
    void concurrentReadersNeverObserveATornCrossFileSwap() throws Exception {
        // Forces many overview() calls to race against a single reload, and asserts every single
        // observation is internally consistent (namespace and the marker embedded in the index's own
        // metadataVersion always agree -- "vN" with "vN"), never one file's old value paired with the
        // other file's new value.
        int readerCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean tornReadObserved = new AtomicBoolean(false);

        List<Future<?>> readers = new java.util.ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            readers.add(pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        // Both fields MUST come from the SAME overview() call -- overview() already
                        // surfaces metadataVersion (read from the index file inside the same read-lock
                        // acquisition that reads namespace from compiled-metadata.json), so a second,
                        // separately-locked service.metadataIndex() call here would open a window
                        // between the two lock acquisitions for the writer to complete an entire swap,
                        // pairing THIS read's namespace with a LATER read's metadataVersion -- a real
                        // cross-call race in the test itself, not evidence of a torn write. Found live:
                        // the first real run failed at the assertFalse below with two individually
                        // valid, but not co-atomic, field reads.
                        var overview = service.overview();
                        String namespace = String.valueOf(overview.get("namespace"));
                        String metadataVersion = String.valueOf(overview.get("metadataVersion"));
                        boolean isOld = namespace.equals("old-namespace") && metadataVersion.equals("v-old");
                        boolean isNew = namespace.equals("race-namespace") && metadataVersion.equals("v-race");
                        if (!isOld && !isNew) {
                            tornReadObserved.set(true);
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        writeFixture(externalIndex, indexJsonWithVersion("v-old"));

        Path raceSourceRoot = appExternalRoot.resolve("reload-source-race");
        stageMetadataAt(raceSourceRoot, "race-namespace", "Race Label", "v-race");
        // The "flip back to old" step below MUST go through the same atomic hot-swap API the race is
        // actually testing, not a raw Files.writeString reset -- a plain (non-atomic,
        // non-lock-protected) file write racing against concurrent readers tears on ITS OWN, which is
        // a bug in this test fixture, not in applyMetadataOnlyReload. Found live: the first real run
        // of this test failed with a Jackson MismatchedInputException from a reader that opened
        // externalIndex mid-truncate during the old writeFixture-based reset.
        Path oldSourceRoot = appExternalRoot.resolve("reload-source-old-reset");
        stageMetadataAt(oldSourceRoot, "old-namespace", "Old Label", "v-old");

        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 50 && !tornReadObserved.get(); i++) {
                    service.applyMetadataOnlyReload("METADATA_ONLY", List.of("race iteration " + i), raceSourceRoot);
                    // Flip back to the old fixture so the next iteration has a real transition to race
                    // against, rather than becoming a no-op after the first apply -- through the SAME
                    // atomic swap path, so this loop only ever exercises applyMetadataOnlyReload's own
                    // atomicity guarantee, never a second, uncoordinated writer.
                    service.applyMetadataOnlyReload("METADATA_ONLY", List.of("reset to old, iteration " + i), oldSourceRoot);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                stop.set(true);
            }
        });

        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<?> reader : readers) {
            reader.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertFalse(tornReadObserved.get(), "a reader observed a torn cross-file swap (old namespace with new index or vice versa)");
    }

    private String firstConceptLabel() {
        @SuppressWarnings("unchecked")
        var items = (List<java.util.Map<String, Object>>) service.concepts(null).get("items");
        return String.valueOf(items.get(0).get("label"));
    }

    private Path stageNewMetadata(String namespace, String label) throws IOException {
        Path sourceRoot = appExternalRoot.resolve("reload-source-" + namespace);
        stageMetadataAt(sourceRoot, namespace, label, "v-new");
        return sourceRoot;
    }

    private void stageMetadataAt(Path sourceRoot, String namespace, String label, String metadataVersion) throws IOException {
        writeFixture(sourceRoot.resolve("src/main/resources/npdev/compiled-metadata.json"), compiledMetadataJson(namespace));
        writeFixture(sourceRoot.resolve("src/main/resources/npdev/metadata/index.json"), indexJsonWithVersion(metadataVersion));
        writeFixture(sourceRoot.resolve("src/main/resources/npdev/metadata/concepts.manifest.json"), conceptsManifestJson(label));
    }

    private static void writeFixture(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String compiledMetadataJson(String namespace) {
        return "{"
                + "\"namespace\":\"" + namespace + "\","
                + "\"dslVersion\":\"2.0\","
                + "\"version\":\"1\","
                + "\"catalogs\":{\"concepts\":{}}"
                + "}";
    }

    private static String indexJson() {
        return indexJsonWithVersion("v-old");
    }

    private static String indexJsonWithVersion(String metadataVersion) {
        return "{"
                + "\"metadataManifestVersion\":\"1.0.0\","
                + "\"metadataVersion\":\"" + metadataVersion + "\","
                + "\"catalogs\":[{\"name\":\"concepts\",\"path\":\"npdev/metadata/concepts.manifest.json\",\"count\":1}]"
                + "}";
    }

    private static String conceptsManifestJson(String label) {
        return "{"
                + "\"metadataManifestVersion\":\"1.0.0\","
                + "\"metadataVersion\":\"1.0.0\","
                + "\"catalog\":\"concepts\","
                + "\"items\":[{\"name\":\"Thing\",\"label\":\"" + label + "\"}]"
                + "}";
    }
}
