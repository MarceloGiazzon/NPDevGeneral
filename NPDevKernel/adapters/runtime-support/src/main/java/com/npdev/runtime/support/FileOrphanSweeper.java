package com.npdev.runtime.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * HARDEN-GC-P3: the pure orphan-sweep algorithm, extracted out of the RuntimeHost Spring service
 * ({@code com.finalexec.filestore.FileOrphanSweepService}) so it runs in a real Gradle test gate
 * ({@code :adapters:expression-cel:test}) instead of only in the RuntimeHost template's src/test,
 * which never executes standalone. The Spring service is now a thin scheduling/config wrapper that
 * delegates here.
 *
 * <p>Reclaims bytes uploaded but never referenced by a saved record: the upload endpoint stores
 * bytes before any record exists, so a dropped create form otherwise leaks forever. A stored object
 * older than the grace window that no live record currently references is deleted; a referenced
 * object is never touched regardless of age, and an object still inside the grace window is left
 * alone (it may be mid-upload or a create request that hasn't saved yet).
 */
public final class FileOrphanSweeper {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private FileOrphanSweeper() {
    }

    /** Outcome of one sweep pass: objects examined, bytes reclaimed, deletes that failed (tolerated). */
    public record SweepResult(int scanned, int deleted, int failed) {
    }

    /**
     * Runs one sweep pass.
     *
     * @param now             the reference instant (injectable so tests are deterministic; production
     *                        passes {@code Instant.now()})
     * @param onDeleteFailure invoked (key, exception) for each tolerated delete failure; may be null.
     *                        A failed delete never aborts the sweep -- it is counted and retried on
     *                        the next pass.
     */
    public static SweepResult sweep(
            FileStoreContract fileStore,
            ConceptStore conceptStore,
            CompiledModel model,
            Duration graceWindow,
            Instant now,
            BiConsumer<String, RuntimeException> onDeleteFailure
    ) {
        if (model == null) {
            return new SweepResult(0, 0, 0);
        }
        Map<String, List<String>> fileFieldsByConcept = fileFieldsByConcept(model);
        if (fileFieldsByConcept.isEmpty()) {
            return new SweepResult(0, 0, 0);
        }

        Instant cutoff = now.minus(graceWindow);
        int scanned = 0;
        int deleted = 0;
        int failed = 0;
        for (String tenantId : fileStore.listTenants()) {
            Set<String> referencedKeys = referencedKeysForTenant(conceptStore, tenantId, fileFieldsByConcept);
            for (FileStoreContract.StoredObject stored : fileStore.list(tenantId)) {
                scanned++;
                if (referencedKeys.contains(stored.key())) {
                    continue;
                }
                if (stored.uploadedAt() != null && stored.uploadedAt().isAfter(cutoff)) {
                    // Still inside the grace window -- may be mid-upload or a create request that
                    // hasn't saved yet; only a positive "unreferenced AND past the window" reaps.
                    continue;
                }
                try {
                    fileStore.delete(new FileHandle("orphan-sweep", stored.key(), "application/octet-stream", 0, "file"));
                    deleted++;
                } catch (RuntimeException exception) {
                    failed++;
                    if (onDeleteFailure != null) {
                        onDeleteFailure.accept(stored.key(), exception);
                    }
                }
            }
        }
        return new SweepResult(scanned, deleted, failed);
    }

    private static Set<String> referencedKeysForTenant(
            ConceptStore conceptStore,
            String tenantId,
            Map<String, List<String>> fileFieldsByConcept
    ) {
        Set<String> referenced = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : fileFieldsByConcept.entrySet()) {
            String conceptName = entry.getKey();
            for (ConceptRecord record : conceptStore.findAll(tenantId, conceptName)) {
                for (String fieldName : entry.getValue()) {
                    Object rawValue = record.data().get(fieldName);
                    if (rawValue == null) {
                        continue;
                    }
                    JsonNode value = OBJECT_MAPPER.valueToTree(rawValue);
                    for (GeneratedCrudRuntimeSupport.FileHandleRef ref : GeneratedCrudRuntimeSupport.extractFileHandleRefs(value)) {
                        referenced.add(ref.key());
                    }
                }
            }
        }
        return referenced;
    }

    private static Map<String, List<String>> fileFieldsByConcept(CompiledModel model) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (CompiledConcept concept : model.getConcepts()) {
            List<String> fileFields = new ArrayList<>();
            for (CompiledField field : concept.getFields()) {
                if ("file".equalsIgnoreCase(field.getDslType())) {
                    fileFields.add(field.getName());
                }
            }
            if (!fileFields.isEmpty()) {
                out.put(concept.getName(), fileFields);
            }
        }
        return out;
    }
}
