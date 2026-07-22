package com.npdev.runtime.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-GC-P1/P2: exercises {@link GeneratedCrudRuntimeSupport}'s file-field cascade helpers
 * against a real (in-memory, fake) {@link FileStoreContract} -- the delete/replace-cascade logic
 * the generated CRUD delete()/update() bodies call into.
 */
class GeneratedCrudRuntimeSupportFileCascadeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void extractFileHandleRefsReadsASingleObjectForMultipleFalse() {
        JsonNode value = handleNode("store-a", "tenant/key-1");
        List<GeneratedCrudRuntimeSupport.FileHandleRef> refs = GeneratedCrudRuntimeSupport.extractFileHandleRefs(value);
        assertEquals(1, refs.size());
        assertEquals("store-a", refs.get(0).storeId());
        assertEquals("tenant/key-1", refs.get(0).key());
    }

    @Test
    void extractFileHandleRefsReadsAnArrayForMultipleTrue() {
        JsonNode value = MAPPER.createArrayNode()
                .add(handleNode("store-a", "tenant/key-1"))
                .add(handleNode("store-a", "tenant/key-2"));
        List<GeneratedCrudRuntimeSupport.FileHandleRef> refs = GeneratedCrudRuntimeSupport.extractFileHandleRefs(value);
        assertEquals(2, refs.size());
    }

    @Test
    void extractFileHandleRefsIsEmptyForNullMissingOrMalformedInput() {
        assertTrue(GeneratedCrudRuntimeSupport.extractFileHandleRefs(null).isEmpty());
        assertTrue(GeneratedCrudRuntimeSupport.extractFileHandleRefs(MAPPER.nullNode()).isEmpty());
        assertTrue(GeneratedCrudRuntimeSupport.extractFileHandleRefs(MAPPER.createObjectNode()).isEmpty());
    }

    @Test
    void deleteFileHandlesDeletesEveryRefAndToleratesAStoreFailure() {
        FakeFileStore store = new FakeFileStore();
        store.put("tenant/ok-1");
        store.put("tenant/ok-2");
        List<GeneratedCrudRuntimeSupport.FileHandleRef> refs = List.of(
                new GeneratedCrudRuntimeSupport.FileHandleRef("store-a", "tenant/ok-1"),
                new GeneratedCrudRuntimeSupport.FileHandleRef("store-a", "tenant/failing-key"),
                new GeneratedCrudRuntimeSupport.FileHandleRef("store-a", "tenant/ok-2")
        );
        store.failOnDeleteKey = "tenant/failing-key";

        GeneratedCrudRuntimeSupport.deleteFileHandles(store, refs, "Doc");

        assertFalse(store.exists("tenant/ok-1"), "ok-1 should be deleted");
        assertFalse(store.exists("tenant/ok-2"), "ok-2 should be deleted even though a sibling delete failed");
        assertTrue(store.deleteAttempted.contains("tenant/failing-key"), "the failing delete must still have been attempted");
    }

    @Test
    void deleteFileHandlesToleratesANullFileStore() {
        // A generated app always wires a FileStoreContract bean, but this must not NPE if it's
        // ever absent (e.g. a unit test wiring only a subset of beans).
        List<GeneratedCrudRuntimeSupport.FileHandleRef> refs = List.of(new GeneratedCrudRuntimeSupport.FileHandleRef("s", "tenant/k"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> GeneratedCrudRuntimeSupport.deleteFileHandles(null, refs, "Doc"));
    }

    @Test
    void cascadeReplacedFileFieldDeletesTheOldHandleWhenReplacedSingleValued() {
        FakeFileStore store = new FakeFileStore();
        store.put("tenant/old-key");
        store.put("tenant/new-key");

        GeneratedCrudRuntimeSupport.cascadeReplacedFileField(
                store, "Doc", handleNode("store-a", "tenant/old-key"), handleNode("store-a", "tenant/new-key"));

        assertFalse(store.exists("tenant/old-key"), "the replaced old file must be deleted");
        assertTrue(store.exists("tenant/new-key"), "the new file must never be touched");
    }

    @Test
    void cascadeReplacedFileFieldDeletesTheOldHandleWhenClearedToNull() {
        FakeFileStore store = new FakeFileStore();
        store.put("tenant/old-key");

        GeneratedCrudRuntimeSupport.cascadeReplacedFileField(
                store, "Doc", handleNode("store-a", "tenant/old-key"), MAPPER.nullNode());

        assertFalse(store.exists("tenant/old-key"));
    }

    @Test
    void cascadeReplacedFileFieldKeepsTheOldHandleWhenValueIsUnchanged() {
        FakeFileStore store = new FakeFileStore();
        store.put("tenant/same-key");

        GeneratedCrudRuntimeSupport.cascadeReplacedFileField(
                store, "Doc", handleNode("store-a", "tenant/same-key"), handleNode("store-a", "tenant/same-key"));

        assertTrue(store.exists("tenant/same-key"), "an unchanged value must never be deleted");
    }

    @Test
    void cascadeReplacedFileFieldOnlyDeletesTheDroppedEntriesForAMultipleValuedField() {
        FakeFileStore store = new FakeFileStore();
        store.put("tenant/keep-1");
        store.put("tenant/drop-1");
        store.put("tenant/added-1");

        JsonNode oldValue = MAPPER.createArrayNode().add(handleNode("s", "tenant/keep-1")).add(handleNode("s", "tenant/drop-1"));
        JsonNode newValue = MAPPER.createArrayNode().add(handleNode("s", "tenant/keep-1")).add(handleNode("s", "tenant/added-1"));

        GeneratedCrudRuntimeSupport.cascadeReplacedFileField(store, "Doc", oldValue, newValue);

        assertTrue(store.exists("tenant/keep-1"), "kept entry must survive");
        assertFalse(store.exists("tenant/drop-1"), "dropped entry must be reclaimed");
        assertTrue(store.exists("tenant/added-1"), "the newly-added entry (never in old) must never be touched");
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode handleNode(String storeId, String key) {
        return MAPPER.createObjectNode()
                .put("storeId", storeId)
                .put("key", key)
                .put("contentType", "text/plain")
                .put("sizeBytes", 3)
                .put("originalName", "x.txt");
    }

    /** Minimal fake so this test doesn't depend on any real adapter module. */
    private static final class FakeFileStore implements FileStoreContract {
        private final Map<String, Boolean> existing = new HashMap<>();
        private final java.util.List<String> deleteAttempted = new java.util.ArrayList<>();
        private String failOnDeleteKey;

        void put(String key) {
            existing.put(key, Boolean.TRUE);
        }

        boolean exists(String key) {
            return existing.getOrDefault(key, Boolean.FALSE);
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
            if (!exists(key)) {
                throw new NoSuchElementException(key);
            }
            return new FileHandle(storeId, key, "text/plain", 3, "x.txt");
        }

        @Override
        public void delete(FileHandle handle) {
            String key = handle.key();
            deleteAttempted.add(key);
            if (Objects.equals(key, failOnDeleteKey)) {
                throw new RuntimeException("simulated store failure for " + key);
            }
            existing.remove(key);
        }

        @Override
        public boolean exists(FileHandle handle) {
            return exists(handle.key());
        }

        @Override
        public java.util.List<String> listTenants() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<FileStoreContract.StoredObject> list(String tenantId) {
            throw new UnsupportedOperationException();
        }
    }
}
