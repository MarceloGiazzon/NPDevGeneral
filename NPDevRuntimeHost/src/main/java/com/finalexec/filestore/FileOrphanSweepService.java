package com.finalexec.filestore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.FileHandle;
import com.npdev.kernel.ports.FileStoreContract;
import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HARDEN-GC-P3: reclaims bytes uploaded but never referenced by a saved record -- the upload
 * endpoint stores bytes before any record exists, so a dropped create form otherwise leaks
 * forever; this is also a safety net for anything the delete/replace cascades in {@link
 * GeneratedCrudRuntimeSupport} can't see. TTL sweep, not two-phase commit (see
 * BOUNDARY_LIFT_HARDENING_ROADMAP.md's GC-P3 write-up for the tradeoff): a stored object older
 * than the grace window that no live record currently references is deleted; a referenced object
 * is never touched regardless of age.
 *
 * <p>Deliberately disabled by default ({@code npdev.filestore.sweep.enabled=false}) -- this
 * deletes bytes, so a deployment should opt in explicitly once its file fields and grace window
 * are confirmed right, rather than this silently start reaping objects the moment the platform
 * version bumps.
 */
@Component
@ConditionalOnProperty(name = "npdev.filestore.sweep.enabled", havingValue = "true")
public class FileOrphanSweepService {
    private static final Logger LOG = Logger.getLogger(FileOrphanSweepService.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final FileStoreContract fileStore;
    private final ObjectProvider<CompiledModel> compiledModel;
    private final ConceptStore conceptStore;
    private final Duration graceWindow;

    public FileOrphanSweepService(
            FileStoreContract fileStore,
            ObjectProvider<CompiledModel> compiledModel,
            ConceptStore conceptStore,
            @Value("${npdev.filestore.sweep.graceHours:24}") long graceHours
    ) {
        this.fileStore = fileStore;
        this.compiledModel = compiledModel;
        this.conceptStore = conceptStore;
        this.graceWindow = Duration.ofHours(graceHours);
    }

    @Scheduled(
            fixedDelayString = "${npdev.filestore.sweep.intervalMs:3600000}",
            initialDelayString = "${npdev.filestore.sweep.initialDelayMs:60000}"
    )
    public void sweepOnSchedule() {
        sweep();
    }

    /** Runs one sweep pass immediately; also the entry point tests call directly. */
    public SweepResult sweep() {
        CompiledModel model = compiledModel.getIfAvailable();
        if (model == null) {
            return new SweepResult(0, 0, 0);
        }
        Map<String, List<String>> fileFieldsByConcept = fileFieldsByConcept(model);
        if (fileFieldsByConcept.isEmpty()) {
            return new SweepResult(0, 0, 0);
        }

        Instant cutoff = Instant.now().minus(graceWindow);
        int scanned = 0;
        int deleted = 0;
        int failed = 0;
        for (String tenantId : fileStore.listTenants()) {
            Set<String> referencedKeys = referencedKeysForTenant(tenantId, fileFieldsByConcept);
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
                    LOG.log(Level.WARNING, "Orphan sweep failed to delete key=" + stored.key()
                            + " (tolerated: retried on the next sweep pass)", exception);
                }
            }
        }
        LOG.info("Orphan sweep: scanned=" + scanned + " deleted=" + deleted + " failed=" + failed);
        return new SweepResult(scanned, deleted, failed);
    }

    private Set<String> referencedKeysForTenant(String tenantId, Map<String, List<String>> fileFieldsByConcept) {
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

    public record SweepResult(int scanned, int deleted, int failed) {
    }
}
