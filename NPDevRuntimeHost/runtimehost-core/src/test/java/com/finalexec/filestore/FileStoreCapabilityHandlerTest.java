package com.finalexec.filestore;

import com.npdev.adapters.filestore.inproc.FileSystemFileStoreAdapter;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.FileHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P5: {@code fileStore.readImage} -- a file field's handle becomes a data: URI, under the caller's tenant only. */
class FileStoreCapabilityHandlerTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @Test
    void readsAStoredImageOfTheCallersTenantAsADataUri(@TempDir Path root) {
        FileSystemFileStoreAdapter store = new FileSystemFileStoreAdapter(root);
        FileHandle handle = store.put("dev", "cap.png", "image/png", PNG_BYTES.length, new ByteArrayInputStream(PNG_BYTES));
        FileStoreCapabilityHandler handler = new FileStoreCapabilityHandler(() -> store);

        CapabilityResult result = handler.invoke(call("readImage",
                Map.of("storeId", handle.storeId(), "key", handle.key())), Map.of("tenantId", "dev"));

        assertTrue(result.ok(), String.valueOf(result.error()));
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertEquals("image/png", value.get("mimeType"));
        assertEquals(PNG_BYTES.length, value.get("sizeBytes"));
        String dataUri = String.valueOf(value.get("dataUri"));
        assertTrue(dataUri.startsWith("data:image/png;base64,"), dataUri);
        assertTrue(Arrays.equals(PNG_BYTES,
                java.util.Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1))));
    }

    @Test
    void anotherTenantsHandleAnEmptyFieldAndANonImageAreRefused(@TempDir Path root) {
        FileSystemFileStoreAdapter store = new FileSystemFileStoreAdapter(root);
        FileHandle foreign = store.put("other", "cap.png", "image/png", PNG_BYTES.length, new ByteArrayInputStream(PNG_BYTES));
        FileHandle text = store.put("dev", "notes.txt", "text/plain", 3, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        FileStoreCapabilityHandler handler = new FileStoreCapabilityHandler(() -> store);
        Map<String, Object> state = Map.of("tenantId", "dev");

        CapabilityResult crossTenant = handler.invoke(call("readImage",
                Map.of("storeId", foreign.storeId(), "key", foreign.key())), state);
        CapabilityResult empty = handler.invoke(call("readImage", ""), state);
        CapabilityResult notAnImage = handler.invoke(call("readImage",
                Map.of("storeId", text.storeId(), "key", text.key())), state);
        CapabilityResult wrongOperation = handler.invoke(call("write", "x"), state);

        assertFalse(crossTenant.ok());
        assertEquals("FILE_STORE_IMAGE_REJECTED", crossTenant.error().code());
        assertEquals("FILE_STORE_FILE_REQUIRED", empty.error().code());
        assertEquals("FILE_STORE_IMAGE_REJECTED", notAnImage.error().code());
        assertEquals("FILE_STORE_OPERATION_UNSUPPORTED", wrongOperation.error().code());
    }

    private static CapabilityCall call(String operation, Object arg) {
        List<Object> args = new ArrayList<>();
        args.add(arg);
        return new CapabilityCall("fileStore", "FileStoreCapability", "fileStore", operation, args, "corr-1", null);
    }
}
