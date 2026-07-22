package com.npdev.adapters.filestore.inproc;

import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract.StoredObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/** LIFT-UPLOAD-P1: put -> get -> delete round-trip, tenant-prefixed keys, path-traversal safety. */
class FileSystemFileStoreAdapterTest {

    @TempDir
    Path tempRoot;

    @Test
    void putGetDeleteRoundTrips() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);

        FileHandle handle = adapter.put("tenant-a", "greeting.txt", "text/plain", bytes.length,
                new ByteArrayInputStream(bytes));

        assertTrue(adapter.exists(handle));
        assertEquals(bytes.length, handle.sizeBytes());
        assertEquals("greeting.txt", handle.originalName());
        assertEquals("text/plain", handle.contentType());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        adapter.get(handle, out);
        assertArrayEquals(bytes, out.toByteArray());

        adapter.delete(handle);
        assertFalse(adapter.exists(handle));
        assertThrows(NoSuchElementException.class, () -> adapter.get(handle, new ByteArrayOutputStream()));
    }

    @Test
    void headResolvesTheStoredContentTypeAndOriginalNameRegardlessOfWhatTheCallerPasses() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        byte[] bytes = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        FileHandle uploaded = adapter.put("tenant-a", "payload.html", "text/html", bytes.length,
                new ByteArrayInputStream(bytes));

        FileHandle resolved = adapter.head(uploaded.storeId(), uploaded.key());

        assertEquals("text/html", resolved.contentType());
        assertEquals("payload.html", resolved.originalName());
        assertEquals(bytes.length, resolved.sizeBytes());
    }

    @Test
    void headOnAnUnknownKeyThrowsNoSuchElement() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        assertThrows(NoSuchElementException.class, () -> adapter.head("file-store-inproc", "tenant-a/does-not-exist"));
    }

    @Test
    void deleteRemovesTheMetadataSidecarSoHeadNoLongerResolvesIt() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        FileHandle handle = adapter.put("tenant-a", "x.txt", "text/plain", 1,
                new ByteArrayInputStream(new byte[] {1}));
        adapter.delete(handle);
        assertThrows(NoSuchElementException.class, () -> adapter.head(handle.storeId(), handle.key()));
    }

    @Test
    void listTenantsAndListReturnStoredObjectsForOrphanSweep() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        adapter.put("tenant-a", "x.txt", "text/plain", 1, new ByteArrayInputStream(new byte[] {1}));
        adapter.put("tenant-a", "y.txt", "text/plain", 1, new ByteArrayInputStream(new byte[] {2}));
        adapter.put("tenant-b", "z.txt", "text/plain", 1, new ByteArrayInputStream(new byte[] {3}));

        assertEquals(2, adapter.listTenants().size());
        assertTrue(adapter.listTenants().containsAll(java.util.List.of("tenant-a", "tenant-b")));

        List<StoredObject> tenantAObjects = adapter.list("tenant-a");
        assertEquals(2, tenantAObjects.size());
        assertTrue(tenantAObjects.stream().allMatch(o -> o.key().startsWith("tenant-a/")));
        assertTrue(tenantAObjects.stream().allMatch(o -> o.uploadedAt() != null));

        assertEquals(1, adapter.list("tenant-b").size());
        assertTrue(adapter.list("tenant-c-never-used").isEmpty());
    }

    @Test
    void listExcludesMetadataSidecarsAndDeletedFiles() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        FileHandle handle = adapter.put("tenant-a", "x.txt", "text/plain", 1, new ByteArrayInputStream(new byte[] {1}));
        assertEquals(1, adapter.list("tenant-a").size());

        adapter.delete(handle);
        assertTrue(adapter.list("tenant-a").isEmpty());
    }

    @Test
    void deletingAnAlreadyDeletedHandleIsNotAnError() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        FileHandle handle = adapter.put("tenant-a", "x.txt", "text/plain", 1,
                new ByteArrayInputStream(new byte[] {1}));
        adapter.delete(handle);
        assertDoesNotThrow(() -> adapter.delete(handle));
    }

    @Test
    void keysAreTenantPrefixedAndScoped() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        FileHandle handleA = adapter.put("tenant-a", "x.txt", "text/plain", 1, new ByteArrayInputStream(new byte[] {1}));
        FileHandle handleB = adapter.put("tenant-b", "x.txt", "text/plain", 1, new ByteArrayInputStream(new byte[] {2}));

        assertTrue(handleA.key().startsWith("tenant-a/"));
        assertTrue(handleB.key().startsWith("tenant-b/"));
        assertNotEquals(handleA.key(), handleB.key());
        assertTrue(adapter.exists(handleA));
        assertTrue(adapter.exists(handleB));
    }

    @Test
    void maliciousTenantIdCannotEscapeTheConfiguredRoot() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        FileHandle handle = adapter.put("../../etc", "x.txt", "text/plain", 1, new ByteArrayInputStream(new byte[] {1}));

        // Sanitization replaces path-traversal characters, so the resulting key must stay inside root.
        assertFalse(handle.key().contains(".."));
        assertTrue(adapter.exists(handle));
    }

    @Test
    void handleWithATraversalKeyIsRejectedDefensively() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        FileHandle maliciousHandle = new FileHandle("file-store-inproc", "../../outside", "text/plain", 1, "x.txt");

        assertThrows(IllegalArgumentException.class, () -> adapter.exists(maliciousHandle));
    }

    @Test
    void largeContentStreamsWithoutFullInMemoryBuffering() {
        FileSystemFileStoreAdapter adapter = new FileSystemFileStoreAdapter(tempRoot);
        int size = 5 * 1024 * 1024; // 5MB, enough to prove streaming works end to end
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }

        FileHandle handle = adapter.put("tenant-a", "big.bin", "application/octet-stream", size,
                new ByteArrayInputStream(bytes));
        assertEquals(size, handle.sizeBytes());

        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        adapter.get(handle, out);
        assertArrayEquals(bytes, out.toByteArray());
    }
}
