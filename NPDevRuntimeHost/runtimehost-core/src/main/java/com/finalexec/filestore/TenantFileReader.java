package com.finalexec.filestore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reads the bytes behind a {@code file} field's value for platform code that hands them on -- the
 * {@code externalAi} vision input (P4) and the {@code fileStore.readImage} capability (P5). Extracted
 * from {@code ExternalAiPromptRunner} so both share ONE tenant check.
 *
 * <p>The value is the field's stored handle (a map, or that map as JSON text) or an inline
 * {@code data:image/...;base64,} URI. Never a URL -- nothing here fetches from the network. A handle is
 * honoured only when its key sits under the caller's own tenant, so a crafted handle cannot read
 * another tenant's file. Every refusal is an {@link IllegalArgumentException} with a caller-safe message.
 */
public final class TenantFileReader {

    public static final Set<String> IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif", "image/heic");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Supplier<FileStoreContract> fileStore;

    public TenantFileReader(Supplier<FileStoreContract> fileStore) {
        this.fileStore = fileStore == null ? () -> null : fileStore;
    }

    public record ImageInput(byte[] bytes, String mimeType) {

        public String dataUri() {
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
    }

    public ImageInput readImage(Object value, String tenantId, long maxBytes) {
        if (value instanceof String text && text.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = text.indexOf(',');
            String header = comma < 0 ? "" : text.substring(5, comma);
            if (comma < 0 || !header.toLowerCase(Locale.ROOT).endsWith(";base64")) {
                throw new IllegalArgumentException("image data URI must be base64-encoded");
            }
            String mime = header.substring(0, header.length() - ";base64".length());
            byte[] bytes = Base64.getDecoder().decode(text.substring(comma + 1));
            return checkedImage(bytes, mime, maxBytes);
        }
        Map<String, Object> handleMap;
        if (value instanceof Map<?, ?> map) {
            handleMap = stringKeys(map);
        } else if (value instanceof String text && text.trim().startsWith("{")) {
            try {
                handleMap = stringKeys(MAPPER.readValue(text, Map.class));
            } catch (Exception exception) {
                throw new IllegalArgumentException("image field holds unparseable file-handle JSON");
            }
        } else {
            throw new IllegalArgumentException("image field must hold a stored file handle or a data: URI");
        }
        Object storeId = handleMap.get("storeId");
        Object key = handleMap.get("key");
        if (storeId == null || key == null) {
            throw new IllegalArgumentException("image file handle needs storeId and key");
        }
        String keyText = String.valueOf(key);
        String tenant = tenantId == null ? "" : tenantId;
        String tenantSegment = keyText.contains("/") ? keyText.substring(0, keyText.indexOf('/')) : "";
        if (!tenantSegment.equals(tenant) && !tenantSegment.equals(tenant.replaceAll("[^A-Za-z0-9._-]", "_"))) {
            throw new IllegalArgumentException("image file handle does not belong to the caller's tenant");
        }
        FileStoreContract store = fileStore.get();
        if (store == null) {
            throw new IllegalArgumentException("no file store is configured to read the image from");
        }
        try {
            FileHandle handle = store.head(String.valueOf(storeId), keyText);
            if (handle.sizeBytes() > maxBytes) {
                throw new IllegalArgumentException("image is larger than " + maxBytes + " bytes");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            store.get(handle, out);
            return checkedImage(out.toByteArray(), handle.contentType(), maxBytes);
        } catch (NoSuchElementException exception) {
            throw new IllegalArgumentException("image file handle is no longer resolvable");
        }
    }

    private static ImageInput checkedImage(byte[] bytes, String mime, long maxBytes) {
        String normalized = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        if (!IMAGE_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("image content type '" + mime + "' is not a supported image type");
        }
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new IllegalArgumentException("image must be 1.." + maxBytes + " bytes");
        }
        return new ImageInput(bytes, normalized);
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
