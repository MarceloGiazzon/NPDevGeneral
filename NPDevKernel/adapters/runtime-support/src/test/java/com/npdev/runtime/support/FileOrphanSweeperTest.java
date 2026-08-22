package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-GC-P3: executed coverage for the orphan-sweep algorithm. This previously lived only in the
 * RuntimeHost template's {@code FileOrphanSweepServiceTest}, which never runs as a standalone Gradle
 * task (the template is compiled only inside a generated FinalApp) -- so the abandoned-upload
 * reclamation path had no gate-enforced test. The logic now lives in {@link FileOrphanSweeper} and
 * is exercised here in {@code :adapters:runtime-support:test}.
 */
class FileOrphanSweeperTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final Duration GRACE = Duration.ofHours(24);
    private static final String TENANT = "tenant-a";

    @Test
    void unreferencedObjectPastGraceWindowIsReaped() {
        FakeFileStore fileStore = new FakeFileStore();
        fileStore.add(TENANT, "tenant-a/orphan", NOW.minus(Duration.ofHours(48)));
        FakeConceptStore conceptStore = new FakeConceptStore(); // no records reference it

        FileOrphanSweeper.SweepResult result =
                FileOrphanSweeper.sweep(fileStore, conceptStore, modelWithFileField(), GRACE, NOW, null);

        assertEquals(new FileOrphanSweeper.SweepResult(1, 1, 0), result);
        assertEquals(List.of("tenant-a/orphan"), fileStore.deletedKeys);
    }

    @Test
    void referencedObjectIsNeverReapedEvenWhenAncient() {
        FakeFileStore fileStore = new FakeFileStore();
        fileStore.add(TENANT, "tenant-a/live", NOW.minus(Duration.ofDays(365)));
        FakeConceptStore conceptStore = new FakeConceptStore();
        conceptStore.addRecordReferencing(TENANT, "Doc", "attachment", "inproc", "tenant-a/live");

        FileOrphanSweeper.SweepResult result =
                FileOrphanSweeper.sweep(fileStore, conceptStore, modelWithFileField(), GRACE, NOW, null);

        assertEquals(new FileOrphanSweeper.SweepResult(1, 0, 0), result);
        assertTrue(fileStore.deletedKeys.isEmpty(), "a referenced object must never be reaped");
    }

    @Test
    void unreferencedObjectWithinGraceWindowIsKept() {
        FakeFileStore fileStore = new FakeFileStore();
        // Uploaded 1h ago -- may be a create form that just hasn't saved its record yet.
        fileStore.add(TENANT, "tenant-a/fresh", NOW.minus(Duration.ofHours(1)));
        FakeConceptStore conceptStore = new FakeConceptStore();

        FileOrphanSweeper.SweepResult result =
                FileOrphanSweeper.sweep(fileStore, conceptStore, modelWithFileField(), GRACE, NOW, null);

        assertEquals(new FileOrphanSweeper.SweepResult(1, 0, 0), result);
        assertTrue(fileStore.deletedKeys.isEmpty());
    }

    @Test
    void deleteFailureIsToleratedCountedAndReported() {
        FakeFileStore fileStore = new FakeFileStore();
        fileStore.add(TENANT, "tenant-a/orphan-1", NOW.minus(Duration.ofHours(48)));
        fileStore.add(TENANT, "tenant-a/boom", NOW.minus(Duration.ofHours(48)));
        fileStore.add(TENANT, "tenant-a/orphan-2", NOW.minus(Duration.ofHours(48)));
        fileStore.failDeleteFor = "tenant-a/boom";
        FakeConceptStore conceptStore = new FakeConceptStore();
        List<String> reportedFailures = new ArrayList<>();

        FileOrphanSweeper.SweepResult result = FileOrphanSweeper.sweep(
                fileStore, conceptStore, modelWithFileField(), GRACE, NOW,
                (key, ex) -> reportedFailures.add(key));

        // One delete fails, but the sweep still processes the other two -- failure is never fatal.
        assertEquals(3, result.scanned());
        assertEquals(2, result.deleted());
        assertEquals(1, result.failed());
        assertEquals(List.of("tenant-a/boom"), reportedFailures);
    }

    @Test
    void modelWithNoFileFieldsScansNothing() {
        FakeFileStore fileStore = new FakeFileStore();
        fileStore.add(TENANT, "tenant-a/whatever", NOW.minus(Duration.ofDays(9)));
        CompiledConcept plain = new CompiledConcept("Plain", "Plain", "plains",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false)));
        CompiledModel model = new CompiledModel("gc.test", "1.0", Map.of("Plain", plain));

        FileOrphanSweeper.SweepResult result =
                FileOrphanSweeper.sweep(fileStore, new FakeConceptStore(), model, GRACE, NOW, null);

        assertEquals(new FileOrphanSweeper.SweepResult(0, 0, 0), result);
        assertTrue(fileStore.listTenantsCalled == 0, "must not even enumerate tenants when no file fields exist");
    }

    @Test
    void nullModelIsANoOp() {
        FileOrphanSweeper.SweepResult result =
                FileOrphanSweeper.sweep(new FakeFileStore(), new FakeConceptStore(), null, GRACE, NOW, null);
        assertEquals(new FileOrphanSweeper.SweepResult(0, 0, 0), result);
    }

    private static CompiledModel modelWithFileField() {
        CompiledConcept doc = new CompiledConcept("Doc", "Doc", "docs", List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField("attachment", "file", "String", false, false, false)
        ));
        return new CompiledModel("gc.test", "1.0", Map.of("Doc", doc));
    }

    // --- fakes -------------------------------------------------------------------------------

    private static final class FakeFileStore implements FileStoreContract {
        private final Map<String, List<StoredObject>> byTenant = new LinkedHashMap<>();
        final List<String> deletedKeys = new ArrayList<>();
        String failDeleteFor = null;
        int listTenantsCalled = 0;

        void add(String tenantId, String key, Instant uploadedAt) {
            byTenant.computeIfAbsent(tenantId, t -> new ArrayList<>()).add(new StoredObject(key, uploadedAt));
        }

        @Override
        public List<String> listTenants() {
            listTenantsCalled++;
            return new ArrayList<>(byTenant.keySet());
        }

        @Override
        public List<StoredObject> list(String tenantId) {
            return byTenant.getOrDefault(tenantId, List.of());
        }

        @Override
        public void delete(FileHandle handle) {
            if (handle.key().equals(failDeleteFor)) {
                throw new IllegalStateException("simulated store outage for " + handle.key());
            }
            deletedKeys.add(handle.key());
        }

        @Override
        public FileHandle put(String tenantId, String originalName, String contentType, long sizeBytes, InputStream content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void get(FileHandle handle, OutputStream destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileHandle head(String storeId, String key) {
            throw new NoSuchElementException();
        }

        @Override
        public boolean exists(FileHandle handle) {
            return false;
        }
    }

    private static final class FakeConceptStore implements ConceptStore {
        private final Map<String, List<ConceptRecord>> byConcept = new LinkedHashMap<>();
        private int nextId = 1;

        void addRecordReferencing(String tenantId, String conceptName, String field, String storeId, String key) {
            Map<String, Object> handle = new LinkedHashMap<>();
            handle.put("storeId", storeId);
            handle.put("key", key);
            byConcept.computeIfAbsent(conceptName, c -> new ArrayList<>())
                    .add(new ConceptRecord(conceptName, "rec-" + (nextId++), tenantId, Map.of(field, handle)));
        }

        @Override
        public List<ConceptRecord> findAll(String tenantId, String conceptName) {
            List<ConceptRecord> out = new ArrayList<>();
            for (ConceptRecord record : byConcept.getOrDefault(conceptName, List.of())) {
                if (record.tenantId().equals(tenantId)) {
                    out.add(record);
                }
            }
            return out;
        }

        @Override
        public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
            return Optional.empty();
        }

        @Override
        public ConceptRecord save(ConceptRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteById(String tenantId, String conceptName, String id) {
            throw new UnsupportedOperationException();
        }
    }
}
