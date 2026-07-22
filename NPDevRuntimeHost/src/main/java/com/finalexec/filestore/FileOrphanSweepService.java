package com.finalexec.filestore;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.FileStoreContract;
import com.npdev.runtime.support.FileOrphanSweeper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HARDEN-GC-P3: reclaims bytes uploaded but never referenced by a saved record -- the upload
 * endpoint stores bytes before any record exists, so a dropped create form otherwise leaks
 * forever; this is also a safety net for anything the delete/replace cascades can't see. TTL
 * sweep, not two-phase commit (see BOUNDARY_LIFT_HARDENING_ROADMAP.md's GC-P3 write-up for the
 * tradeoff): a stored object older than the grace window that no live record currently references
 * is deleted; a referenced object is never touched regardless of age. The reclamation algorithm
 * lives in {@link FileOrphanSweeper} (gate-tested); this class is its Spring/config/scheduling
 * wrapper.
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

    /**
     * Runs one sweep pass immediately; also the entry point tests call directly. The reclamation
     * algorithm itself lives in {@link FileOrphanSweeper} (in the expression-cel adapter) so it is
     * exercised by a real Gradle test gate; this method is just its Spring/config/logging wrapper.
     */
    public SweepResult sweep() {
        FileOrphanSweeper.SweepResult result = FileOrphanSweeper.sweep(
                fileStore,
                conceptStore,
                compiledModel.getIfAvailable(),
                graceWindow,
                Instant.now(),
                (key, exception) -> LOG.log(Level.WARNING, "Orphan sweep failed to delete key=" + key
                        + " (tolerated: retried on the next sweep pass)", exception)
        );
        LOG.info("Orphan sweep: scanned=" + result.scanned()
                + " deleted=" + result.deleted() + " failed=" + result.failed());
        return new SweepResult(result.scanned(), result.deleted(), result.failed());
    }

    public record SweepResult(int scanned, int deleted, int failed) {
    }
}
