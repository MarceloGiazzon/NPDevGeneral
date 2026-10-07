package com.finalexec.filestore;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.FileStoreContract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * P5 (Pigmentampas photo -> mosaic): the flow-facing {@code fileStore} capability -- registered for a
 * model binding {@code { "capability": "fileStore", "adapter": "fileStore" }}, platform-served like
 * {@code externalAi}, never a plugin contribution.
 *
 * <p>Why it exists: a {@code customCapabilities} Java plugin is sandboxed (SEC-3 bars {@code java/io/}
 * file access and it is constructed with no platform services), so it cannot open a {@code file} field
 * itself. {@code readImage} turns the field's stored handle into an inline {@code data:} URI the plugin
 * can decode in memory, under the SAME tenant check the {@code externalAi} vision input uses
 * ({@link TenantFileReader}):
 *
 * <pre>
 *   { "type": "capabilityCall", "capability": "fileStore", "operation": "readImage",
 *     "args": { "file": "$input.sourceImage" }, "target": "photo" }
 *   // $photo = { "dataUri": "data:image/png;base64,...", "mimeType": "image/png", "sizeBytes": 48211 }
 * </pre>
 */
public final class FileStoreCapabilityHandler implements CapabilityAdapter {

    public static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    private final TenantFileReader reader;

    public FileStoreCapabilityHandler(Supplier<FileStoreContract> fileStore) {
        this.reader = new TenantFileReader(fileStore);
    }

    @Override
    public String adapterId() {
        return "fileStore";
    }

    @Override
    public String capability() {
        return "fileStore";
    }

    @Override
    public String capabilityType() {
        return "FileStoreCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if (!"readImage".equals(call.operation())) {
            return CapabilityResult.failure("FILE_STORE_OPERATION_UNSUPPORTED",
                    "Unsupported fileStore operation: " + call.operation() + " (flows call 'readImage')",
                    CapabilityErrorKind.CONTRACT, Map.of("operation", String.valueOf(call.operation())));
        }
        List<Object> args = call.args() == null ? List.of() : call.args();
        Object value = args.isEmpty() ? null : args.get(0);
        if (value == null || (value instanceof String text && text.isBlank())) {
            return CapabilityResult.failure("FILE_STORE_FILE_REQUIRED",
                    "No image to read -- upload one into the file field first.",
                    CapabilityErrorKind.CONTRACT, Map.of());
        }
        Object tenant = contextState == null ? null : contextState.get("tenantId");
        String tenantId = tenant == null ? "default" : String.valueOf(tenant);
        TenantFileReader.ImageInput image;
        try {
            image = reader.readImage(value, tenantId, MAX_IMAGE_BYTES);
        } catch (IllegalArgumentException exception) {
            return CapabilityResult.failure("FILE_STORE_IMAGE_REJECTED", exception.getMessage(),
                    CapabilityErrorKind.CONTRACT, Map.of());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataUri", image.dataUri());
        out.put("mimeType", image.mimeType());
        out.put("sizeBytes", image.bytes().length);
        return CapabilityResult.success(out);
    }
}
