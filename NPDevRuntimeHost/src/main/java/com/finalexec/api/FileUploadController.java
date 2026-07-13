package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFileMetadata;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * LIFT-UPLOAD-P3: the only server-side multipart surface in the platform. Uploads validate the
 * target field's declared {@code file} metadata (contentTypes/maxSizeBytes), store bytes through
 * {@link FileStoreContract}, and hand back a {@link FileHandle} for the caller to persist on the
 * record via ordinary CRUD -- this controller never touches {@code ConceptGateway} itself.
 *
 * <p>Tenant isolation: every key this platform issues is {@code <tenantId>/<uuid>}
 * ({@link com.npdev.adapters.filestore.inproc.FileSystemFileStoreAdapter}), so a caller can only
 * read/delete a handle whose key's tenant segment matches their own request context tenant.
 *
 * <p>Not built in this phase (documented gap, not silently skipped): orphan-cleanup on record
 * delete/replace requires hooking every generated CRUD service's delete path, a larger
 * cross-cutting change out of scope here.
 */
@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private final FileStoreContract fileStore;
    private final ObjectProvider<CompiledModel> compiledModel;
    private final RuntimeContextService runtimeContextService;

    public FileUploadController(
            FileStoreContract fileStore,
            ObjectProvider<CompiledModel> compiledModel,
            RuntimeContextService runtimeContextService
    ) {
        this.fileStore = fileStore;
        this.compiledModel = compiledModel;
        this.runtimeContextService = runtimeContextService;
    }

    @PostMapping("/{conceptName}/{fieldName}")
    public Map<String, Object> upload(
            HttpServletRequest request,
            @PathVariable String conceptName,
            @PathVariable String fieldName,
            @RequestPart("file") MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must be non-empty");
        }
        CompiledField field = requireFileField(conceptName, fieldName);
        CompiledFileMetadata meta = field.getFile();
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        if (meta != null && !meta.contentTypes().isEmpty()
                && meta.contentTypes().stream().noneMatch(allowed -> allowed.equalsIgnoreCase(contentType))) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "content type " + contentType + " is not allowed for " + conceptName + "." + fieldName
                            + " (allowed: " + meta.contentTypes() + ")");
        }
        if (meta != null && meta.maxSizeBytes() != null && file.getSize() > meta.maxSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "file size " + file.getSize() + " exceeds the " + meta.maxSizeBytes() + " byte limit for "
                            + conceptName + "." + fieldName);
        }

        ExecutionContext context = runtimeContextService.currentContext(request);
        try {
            FileHandle handle = fileStore.put(
                    context.tenantId(), file.getOriginalFilename(), contentType, file.getSize(), file.getInputStream());
            return toHandleMap(handle);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    @GetMapping
    public void download(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam String storeId,
            @RequestParam String key,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String originalName
    ) {
        requireOwnedByCaller(request, key);
        FileHandle handle = new FileHandle(
                storeId, key,
                contentType == null || contentType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType,
                0,
                originalName == null || originalName.isBlank() ? "file" : originalName
        );
        response.setContentType(handle.contentType());
        response.setHeader("Content-Disposition", "inline; filename=\"" + sanitizeHeaderValue(handle.originalName()) + "\"");
        try {
            fileStore.get(handle, response.getOutputStream());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No file for key: " + key);
        }
    }

    @DeleteMapping
    public void delete(HttpServletRequest request, @RequestParam String storeId, @RequestParam String key) {
        requireOwnedByCaller(request, key);
        fileStore.delete(new FileHandle(storeId, key, MediaType.APPLICATION_OCTET_STREAM_VALUE, 0, "file"));
    }

    private void requireOwnedByCaller(HttpServletRequest request, String key) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        String callerTenant = normalizeTenantSegment(context.tenantId());
        String keyTenant = key.contains("/") ? key.substring(0, key.indexOf('/')) : "";
        if (!callerTenant.equalsIgnoreCase(keyTenant)) {
            // Worded like a not-found, not a forbidden, so it never confirms a key exists in
            // another tenant -- same defensive wording used elsewhere for cross-tenant checks.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No file for key: " + key);
        }
    }

    private static String normalizeTenantSegment(String tenantId) {
        String trimmed = tenantId == null ? "" : tenantId.trim();
        return trimmed.isEmpty() ? "default" : trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private CompiledField requireFileField(String conceptName, String fieldName) {
        CompiledModel model = compiledModel.getIfAvailable();
        if (model == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Compiled model is not available");
        }
        Optional<CompiledConcept> concept = model.getConcepts().stream()
                .filter(c -> c.getName().equalsIgnoreCase(conceptName))
                .findFirst();
        if (concept.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Concept not found: " + conceptName);
        }
        Optional<CompiledField> field = concept.get().getFields().stream()
                .filter(f -> f.getName().equalsIgnoreCase(fieldName))
                .findFirst();
        if (field.isEmpty() || !"file".equalsIgnoreCase(field.get().getDslType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Field " + conceptName + "." + fieldName + " is not a file field");
        }
        return field.get();
    }

    private static Map<String, Object> toHandleMap(FileHandle handle) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeId", handle.storeId());
        out.put("key", handle.key());
        out.put("contentType", handle.contentType());
        out.put("sizeBytes", handle.sizeBytes());
        out.put("originalName", handle.originalName());
        return out;
    }

    private static String sanitizeHeaderValue(String value) {
        return value.replace("\"", "'").replace("\r", "").replace("\n", "");
    }
}
