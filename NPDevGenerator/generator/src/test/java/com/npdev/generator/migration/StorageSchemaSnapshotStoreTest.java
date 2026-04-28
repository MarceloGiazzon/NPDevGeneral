package com.npdev.generator.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageSchemaSnapshotStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistAndLoadCanonicalSnapshotDeterministically() throws Exception {
        StorageSchemaSnapshot snapshot = new StorageSchemaSnapshot(
                "compiled-model",
                List.of(
                        new StorageTableSchema(
                                "user",
                                List.of(
                                        new StorageColumnSchema("email", "VARCHAR", true, true),
                                        new StorageColumnSchema("id", "UUID", true, true)
                                )
                        )
                )
        );

        StorageSchemaSnapshotStore store = new StorageSchemaSnapshotStore();
        Path file = tempDir.resolve("latest-storage-schema.json");

        store.save(file, snapshot);
        StorageSchemaSnapshot loaded = store.loadIfExists(file);

        assertEquals(store.toCanonicalJson(snapshot), store.toCanonicalJson(loaded));
        assertEquals(store.computeCanonicalHash(snapshot), store.computeCanonicalHash(loaded));
    }
}
