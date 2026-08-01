package com.finalexec.controlpanel;

import com.finalexec.db.CrossEngineDataPromotion;
import com.finalexec.db.MigrationClaimStore;
import com.finalexec.db.MigrationMarkStore;
import com.finalexec.db.PendingSchemaAcknowledgmentStore;
import com.finalexec.db.SchemaDropSnapshotRestorer;
import com.finalexec.db.SchemaLifecycleExecutor;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LNCH-1 Phase 6 (task 6.2a). ControlPanel surface for the pre-authorization flow ratified for the
 * destructive-acknowledgment UX (plan.md §2.6 answer 2): an operator reviews a migration plan
 * (printed by {@code Build-NpdevApp.ps1 -PlanOnly}/{@code -Upgrade}) and pastes its
 * {@code toFingerprint} + {@code ackToken} into this screen on the CURRENTLY RUNNING (old) app --
 * BEFORE the new jar that will actually need the acknowledgment is even deployed, since a refused
 * boot has no server left to serve a ControlPanel page on. Writes a row into
 * {@link PendingSchemaAcknowledgmentStore}, which the new app's {@code SchemaLifecycleExecutor}
 * consults at boot in addition to the static generated manifest field.
 *
 * <p>SUPERUSER-gated, following {@code ControlPanelSchedulesController}/{@code ControlPanelAdminUserController}'s
 * exact pattern (manual {@code hasRole("SUPERUSER")} check via {@link RuntimeContextService}, not
 * an annotation). Mapped at {@code /api/admin/schema-migration} -- confirmed not to collide with
 * any existing mapping (grepped the RuntimeHost source tree before choosing it).
 *
 * <p>Deliberately does NOT attempt to discover {@code migration-plan.json} from any filesystem
 * convention (e.g. a {@code Build\<app>\migration-plans\} directory) -- that would hardcode a
 * dev-environment-only path into the RuntimeHost template, which is copied into every generated
 * app regardless of where or how it is deployed. The operator supplies the fingerprint/token by
 * hand (copy-pasted from the CLI's printed plan); {@code itemsJson} is optional and purely for
 * audit/display -- it is never validated against anything, it just rides along on the row so the
 * "pending" list is self-describing without a second lookup.
 */
@RestController
@RequestMapping("/api/admin/schema-migration")
public class SchemaAcknowledgmentController {

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final RuntimeContextService runtimeContextService;

    public SchemaAcknowledgmentController(
            ObjectProvider<DataSource> dataSourceProvider,
            RuntimeContextService runtimeContextService
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.runtimeContextService = runtimeContextService;
    }

    public record AcknowledgeRequest(String toFingerprint, String ackToken, String itemsJson) {
    }

    @PostMapping("/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledge(
            @RequestBody AcknowledgeRequest request, HttpServletRequest httpRequest
    ) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();

        String toFingerprint = request.toFingerprint() == null ? null : request.toFingerprint().trim();
        String ackToken = request.ackToken() == null ? null : request.ackToken().trim();
        if (toFingerprint == null || toFingerprint.isBlank() || ackToken == null || ackToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_required_field",
                    "detail", "both toFingerprint and ackToken are required"));
        }

        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        PendingSchemaAcknowledgmentStore.PendingAcknowledgment inserted = PendingSchemaAcknowledgmentStore.insert(
                dataSource, toFingerprint, ackToken, request.itemsJson(), context.actorId());

        return ResponseEntity.status(201).body(toResponseBody(inserted));
    }

    @GetMapping("/pending")
    public List<Map<String, Object>> pending(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();

        return PendingSchemaAcknowledgmentStore.listAll(dataSource).stream()
                .map(SchemaAcknowledgmentController::toResponseBody)
                .toList();
    }

    private static Map<String, Object> toResponseBody(PendingSchemaAcknowledgmentStore.PendingAcknowledgment row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.id());
        body.put("toFingerprint", row.toFingerprint());
        body.put("ackToken", row.ackToken());
        body.put("itemsJson", row.itemsJson());
        body.put("submittedAtUtc", row.submittedAtUtc());
        body.put("submittedBy", row.submittedBy());
        return body;
    }

    // ---- REG-7.2: "mark migration as done" (D2 -- ControlPanel-only v1, no generator/CLI round-trip) ----

    public record MarkDoneRequest(String fromFingerprint, String toFingerprint, String note) {
    }

    /**
     * REG-7.2. GeneXus-style "the schema is already at this fingerprint; stop trying to migrate to
     * it." Submitted on the CURRENTLY RUNNING app (same reasoning as {@link #acknowledge}: the boot
     * this authorizes has no server of its own to accept the mark on). {@code SchemaLifecycleExecutor}
     * (via {@link MigrationMarkStore}) consumes it on the next boot whose OWN live stored fingerprint
     * still equals {@code fromFingerprint} and whose target equals {@code toFingerprint} (REG-28: bound
     * to that exact transition, not just the target, so a leftover/abandoned mark can never fast-
     * forward an unrelated boot). The operator reads both values off the SAME migration plan printed by
     * {@code Build-NpdevApp.ps1 -PlanOnly}/{@code -Upgrade} -- it already prints the pair.
     */
    @PostMapping("/mark-done")
    public ResponseEntity<Map<String, Object>> markDone(
            @RequestBody MarkDoneRequest request, HttpServletRequest httpRequest
    ) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();

        String fromFingerprint = request.fromFingerprint() == null ? null : request.fromFingerprint().trim();
        String toFingerprint = request.toFingerprint() == null ? null : request.toFingerprint().trim();
        if (fromFingerprint == null || fromFingerprint.isBlank() || toFingerprint == null || toFingerprint.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_required_field",
                    "detail", "both fromFingerprint and toFingerprint are required"));
        }

        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        MigrationMarkStore.Mark inserted = MigrationMarkStore.insert(
                dataSource, fromFingerprint, toFingerprint, context.actorId(), request.note());

        return ResponseEntity.status(201).body(toResponseBody(inserted));
    }

    @GetMapping("/marks")
    public List<Map<String, Object>> marks(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();

        return MigrationMarkStore.listAll(dataSource).stream()
                .map(SchemaAcknowledgmentController::toResponseBody)
                .toList();
    }

    private static Map<String, Object> toResponseBody(MigrationMarkStore.Mark row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.id());
        body.put("fromFingerprint", row.fromFingerprint());
        body.put("markedFingerprint", row.markedFingerprint());
        body.put("markedAtUtc", row.markedAtUtc());
        body.put("markedBy", row.markedBy());
        body.put("note", row.note());
        return body;
    }

    // ---- REG-7.3: collision detection -- inspect / manually clear a stale migration claim ----

    /**
     * REG-7.3 (D3). The claim {@code SchemaLifecycleExecutor} takes at the top of every upgrade boot
     * to serialize concurrent migrations. Present means another instance is either genuinely mid-
     * migration right now, or crashed while holding it.
     */
    @GetMapping("/claim")
    public Map<String, Object> claim(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();

        Optional<MigrationClaimStore.Claim> current = MigrationClaimStore.current(dataSource);
        if (current.isEmpty()) {
            return Map.of("held", false);
        }
        MigrationClaimStore.Claim claim = current.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("held", true);
        body.put("instanceId", claim.instanceId());
        body.put("hostname", claim.hostname());
        body.put("claimedAtUtc", claim.claimedAtUtc());
        return body;
    }

    /**
     * REG-7.3's manual escape hatch (D3): unconditionally deletes the claim row, regardless of who
     * holds it -- for the crashed-holder case. Clearing a claim while another instance genuinely
     * holds it re-introduces the exact race this feature detects; that is an operator decision this
     * endpoint trusts the (SUPERUSER) caller to make deliberately.
     */
    @PostMapping("/clear-claim")
    public ResponseEntity<Map<String, Object>> clearClaim(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();

        MigrationClaimStore.clear(dataSource);
        return ResponseEntity.ok(Map.of("cleared", true));
    }

    // ---- Move 9 B3 (docs/ACCEPTED_BOUNDARIES.md B9): operator-driven pre-drop-snapshot restore ----

    /**
     * Every {@code runtime-data/schema-snapshot-before-drop/<timestamp>} directory {@link
     * com.finalexec.db.SchemaDropSnapshotWriter} has written on THIS instance, most recent first.
     * Read-only; writes nothing.
     */
    @GetMapping("/snapshots")
    public List<String> snapshots(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        return SchemaDropSnapshotRestorer.listSnapshots();
    }

    /** Every table the given snapshot captured (one entry per {@code <table>.jsonl} it wrote). */
    @GetMapping("/snapshots/{snapshot}/tables")
    public List<String> snapshotTables(@PathVariable String snapshot, HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        return SchemaDropSnapshotRestorer.tablesInSnapshot(snapshot);
    }

    /**
     * Dry-run: compares the snapshot's rows for {@code table} against the CURRENTLY LIVE table (by
     * {@code id}) and reports how many would be inserted, how many already match live, and which ids
     * conflict (present live with DIFFERENT content -- never auto-resolved). Writes nothing. This is
     * the sanctioned way to inspect a restore before committing to {@link #restoreSnapshotTable}.
     */
    @GetMapping("/snapshots/{snapshot}/tables/{table}/preview")
    public Map<String, Object> previewSnapshotTable(
            @PathVariable String snapshot, @PathVariable String table, HttpServletRequest httpRequest
    ) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();
        SchemaDropSnapshotRestorer.Preview preview = SchemaDropSnapshotRestorer.preview(dataSource, snapshot, table);
        return toResponseBody(preview);
    }

    /**
     * Applies the restore for ONE explicit (snapshot, table) pair -- there is deliberately no
     * "restore everything" call. INSERTs every snapshot row missing from the live table; a row
     * already live with identical content is skipped; a row live with DIFFERENT content is reported
     * as a conflict and left untouched for the operator to resolve by hand. Refuses if the live table
     * does not exist yet (restore is DATA-only, never schema -- boot the app normally first).
     */
    @PostMapping("/snapshots/{snapshot}/tables/{table}/restore")
    public ResponseEntity<Map<String, Object>> restoreSnapshotTable(
            @PathVariable String snapshot, @PathVariable String table, HttpServletRequest httpRequest
    ) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();
        try {
            SchemaDropSnapshotRestorer.RestoreResult result = SchemaDropSnapshotRestorer.apply(dataSource, snapshot, table);
            return ResponseEntity.ok(toResponseBody(result));
        } catch (IllegalStateException | IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }

    private static Map<String, Object> toResponseBody(SchemaDropSnapshotRestorer.Preview preview) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot", preview.snapshot());
        body.put("table", preview.table());
        body.put("rowsInSnapshot", preview.rowsInSnapshot());
        body.put("rowsToInsert", preview.rowsToInsert());
        body.put("rowsAlreadyPresentIdentical", preview.rowsAlreadyPresentIdentical());
        body.put("conflictingIds", preview.conflictingIds());
        return body;
    }

    private static Map<String, Object> toResponseBody(SchemaDropSnapshotRestorer.RestoreResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot", result.snapshot());
        body.put("table", result.table());
        body.put("rowsInSnapshot", result.rowsInSnapshot());
        body.put("rowsInserted", result.rowsInserted());
        body.put("rowsAlreadyPresentIdentical", result.rowsAlreadyPresentIdentical());
        body.put("conflictingIds", result.conflictingIds());
        return body;
    }

    // ---- Move 9 A4 (docs/ACCEPTED_BOUNDARIES.md B10): operator-driven H2->Postgres data promotion ----

    public record TargetDatabaseRequest(String jdbcUrl, String username, String password) {
    }

    /**
     * Dry-run: per-table source/target row counts and type-mapping notes (JSONB/UUID columns), for
     * every business table this app's manifest declares. Writes nothing to either side. The target
     * connection is built fresh from the request body and closed before returning -- {@code
     * jdbcUrl}/{@code username}/{@code password} are used only for the duration of this call, never
     * logged or persisted.
     */
    @PostMapping("/promote/preview")
    public Map<String, Object> promotePreview(@RequestBody TargetDatabaseRequest request, HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        DataSource source = requireDataSource();
        SchemaLifecycleExecutor.SchemaManifest manifest = requireManifest();
        try (CloseableDataSource target = buildTargetDataSource(request)) {
            CrossEngineDataPromotion.Preview preview = CrossEngineDataPromotion.preview(source, target.dataSource(), manifest);
            return toResponseBody(preview);
        }
    }

    /**
     * Applies the promotion: copies every business table's rows from this app's OWN database (the
     * source) to the target named in the request body. Never issues DDL -- the target table must
     * already exist (boot the app normally pointed at the target database first, which realizes an
     * empty schema via the existing, already engine-agnostic schema-realization path). Takes A1's
     * migration claim on the target for the duration. Always returns a complete per-table report,
     * including any table whose copy failed partway -- never a silent partial run.
     */
    @PostMapping("/promote/apply")
    public ResponseEntity<Map<String, Object>> promoteApply(@RequestBody TargetDatabaseRequest request, HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        DataSource source = requireDataSource();
        SchemaLifecycleExecutor.SchemaManifest manifest = requireManifest();
        try (CloseableDataSource target = buildTargetDataSource(request)) {
            CrossEngineDataPromotion.PromotionResult result = CrossEngineDataPromotion.apply(source, target.dataSource(), manifest);
            Map<String, Object> body = toResponseBody(result);
            return result.allMatched() ? ResponseEntity.ok(body) : ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
    }

    private SchemaLifecycleExecutor.SchemaManifest requireManifest() {
        SchemaLifecycleExecutor.SchemaManifest manifest = SchemaLifecycleExecutor.loadManifest();
        if (manifest == null || !manifest.physicalDatabase()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Promotion requires a physical database source -- this app is running InMemory.");
        }
        return manifest;
    }

    private static Map<String, Object> toResponseBody(CrossEngineDataPromotion.Preview preview) {
        List<Map<String, Object>> tableCounts = preview.tableCounts().stream().map(count -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("table", count.table());
            body.put("sourceRowCount", count.sourceRowCount());
            body.put("targetRowCountBefore", count.targetRowCountBefore());
            return body;
        }).toList();
        List<Map<String, Object>> notes = preview.notes().stream().map(note -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("table", note.table());
            body.put("column", note.column());
            body.put("sqlType", note.sqlType());
            body.put("note", note.note());
            return body;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tableCounts", tableCounts);
        body.put("typeMappingNotes", notes);
        return body;
    }

    private static Map<String, Object> toResponseBody(CrossEngineDataPromotion.PromotionResult result) {
        List<Map<String, Object>> tables = result.tables().stream().map(table -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("table", table.table());
            body.put("sourceRowCount", table.sourceRowCount());
            body.put("rowsCopied", table.rowsCopied());
            body.put("targetRowCountAfter", table.targetRowCountAfter());
            body.put("matched", table.matched());
            body.put("error", table.error());
            return body;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tables", tables);
        body.put("allMatched", result.allMatched());
        return body;
    }

    private record CloseableDataSource(DataSource dataSource, AutoCloseable underlying) implements AutoCloseable {
        @Override
        public void close() {
            try {
                underlying.close();
            } catch (Exception exception) {
                // Best-effort: the target connection pool failing to close cleanly must never mask
                // the promotion result already computed and about to be returned to the operator.
            }
        }
    }

    private static CloseableDataSource buildTargetDataSource(TargetDatabaseRequest request) {
        if (request == null || request.jdbcUrl() == null || request.jdbcUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jdbcUrl is required");
        }
        DataSource dataSource = DataSourceBuilder.create()
                .url(request.jdbcUrl())
                .username(request.username())
                .password(request.password())
                .build();
        AutoCloseable closeable = dataSource instanceof AutoCloseable autoCloseable ? autoCloseable : () -> { };
        return new CloseableDataSource(dataSource, closeable);
    }

    private DataSource requireDataSource() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ControlPanel unavailable in InMemory mode -- requires a physical database "
                            + "(H2Local/H2Server/Postgres).");
        }
        return dataSource;
    }

    private void requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
