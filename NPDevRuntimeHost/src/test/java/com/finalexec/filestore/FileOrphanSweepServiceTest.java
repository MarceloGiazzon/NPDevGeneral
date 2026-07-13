package com.finalexec.filestore;

import com.npdev.adapters.filestore.inproc.FileSystemFileStoreAdapter;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFileMetadata;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.FileHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * HARDEN-GC-P3: integration smoke test proving {@link FileOrphanSweepService} wires correctly to a
 * real {@link FileSystemFileStoreAdapter} and a real {@link ConceptStore} -- reaps an unreferenced,
 * past-grace-window object; leaves a referenced object alone regardless of age; and leaves a fresh
 * (still-within-grace-window) unreferenced object alone. The reclamation algorithm itself
 * (including the no-file-fields and null-model edge cases) is exercised with fakes and gate-tested
 * coverage by {@code FileOrphanSweeperTest} in {@code :adapters:expression-cel:test} -- this class
 * intentionally does not re-test those cases, only the real-adapter wiring.
 */
class FileOrphanSweepServiceTest {

    @TempDir
    Path tempRoot;

    @Test
    void sweepReapsAnOldUnreferencedObjectButKeepsReferencedAndFreshOnes() throws Exception {
        FileSystemFileStoreAdapter fileStore = new FileSystemFileStoreAdapter(tempRoot);

        FileHandle orphanOld = fileStore.put("dev", "orphan-old.txt", "text/plain", 4,
                new ByteArrayInputStream("orph".getBytes(StandardCharsets.UTF_8)));
        FileHandle referenced = fileStore.put("dev", "kept.txt", "text/plain", 4,
                new ByteArrayInputStream("keep".getBytes(StandardCharsets.UTF_8)));
        FileHandle orphanFresh = fileStore.put("dev", "orphan-fresh.txt", "text/plain", 5,
                new ByteArrayInputStream("fresh".getBytes(StandardCharsets.UTF_8)));

        // Simulate the two "old" objects having been uploaded 2 days ago (the fresh one keeps its
        // real just-now mtime).
        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
        setLastModified(tempRoot.resolve(orphanOld.key()), twoDaysAgo);
        setLastModified(tempRoot.resolve(referenced.key()), twoDaysAgo);

        CompiledModel model = docModel();
        FakeConceptStore conceptStore = new FakeConceptStore();
        conceptStore.save("dev", "Doc", "row-1", Map.of("attachment", handleMap(referenced)));

        FileOrphanSweepService sweep = new FileOrphanSweepService(
                fileStore, objectProvider(model), conceptStore, 24L);

        FileOrphanSweepService.SweepResult result = sweep.sweep();

        assertEquals(3, result.scanned());
        assertEquals(1, result.deleted());
        assertEquals(0, result.failed());

        assertThrows(java.util.NoSuchElementException.class, () -> fileStore.head(orphanOld.storeId(), orphanOld.key()),
                "the old, unreferenced object must be reclaimed");
        fileStore.head(referenced.storeId(), referenced.key()); // must not throw: still referenced
        fileStore.head(orphanFresh.storeId(), orphanFresh.key()); // must not throw: inside grace window
    }

    private static void setLastModified(Path file, Instant instant) throws Exception {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(instant));
    }

    private static Map<String, Object> handleMap(FileHandle handle) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("storeId", handle.storeId());
        map.put("key", handle.key());
        map.put("contentType", handle.contentType());
        map.put("sizeBytes", handle.sizeBytes());
        map.put("originalName", handle.originalName());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CompiledModel> objectProvider(CompiledModel model) {
        ObjectProvider<CompiledModel> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return provider;
    }

    private static CompiledModel docModel() {
        CompiledField idField = new CompiledField("id", "uuid", "java.util.UUID", true, true, true);
        CompiledFileMetadata fileMeta = new CompiledFileMetadata(List.of("text/plain"), null, false);
        CompiledField attachmentField = new CompiledField(
                "attachment", "file", "com.fasterxml.jackson.databind.JsonNode",
                false, false, false,
                List.of(), null, null, null, null, List.of(), null, null, null,
                fileMeta
        );
        CompiledConcept concept = new CompiledConcept("Doc", "Doc", "docs", List.of(idField, attachmentField));
        return new CompiledModel("harden.gc.sweep", "1.0.0", "1.0.0", Map.of(concept.getName(), concept));
    }

    /** Minimal in-memory ConceptStore fake, scoped to what the sweep needs (findAll by tenant/concept). */
    private static final class FakeConceptStore implements ConceptStore {
        private final List<ConceptRecord> records = new ArrayList<>();

        void save(String tenantId, String conceptName, String id, Map<String, Object> data) {
            records.add(new ConceptRecord(conceptName, id, tenantId, data));
        }

        @Override
        public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
            return records.stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.conceptName().equals(conceptName) && r.id().equals(id))
                    .findFirst();
        }

        @Override
        public List<ConceptRecord> findAll(String tenantId, String conceptName) {
            return records.stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.conceptName().equals(conceptName))
                    .toList();
        }

        @Override
        public ConceptRecord save(ConceptRecord record) {
            records.add(record);
            return record;
        }

        @Override
        public void deleteById(String tenantId, String conceptName, String id) {
            records.removeIf(r -> r.tenantId().equals(tenantId) && r.conceptName().equals(conceptName) && r.id().equals(id));
        }
    }
}
