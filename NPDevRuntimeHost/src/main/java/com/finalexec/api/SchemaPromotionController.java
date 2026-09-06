package com.finalexec.api;

import com.finalexec.db.CrossEngineDataPromotion;
import com.finalexec.db.PromotionArc;
import com.finalexec.db.PromotionVerifier;
import com.finalexec.db.SchemaLifecycleExecutor;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B10 (STOR-29, {@code ALL_HITTABLE_LIFT_PLAN_2026-09-05.md} package P6): the one-call REST
 * promotion arc -- realize the target schema, copy every business table's rows, verify -- so a
 * REST caller gets the exact same one-command behaviour {@code npdev db promote} already gave the
 * CLI ({@link com.finalexec.db.PromoteMain}, sharing this endpoint's own {@link PromotionArc}).
 * SUPERUSER-gated, following {@code SchemaAcknowledgmentController}'s exact pattern (manual
 * {@code hasRole("SUPERUSER")} check via {@link RuntimeContextService}, not an annotation).
 *
 * <p>Distinct from {@code SchemaAcknowledgmentController}'s own bare {@code /promote/preview}/
 * {@code /promote/apply} endpoints -- those two assume the target schema already exists, refusing
 * per-table otherwise (the original half of B10's residue this package closes). This endpoint
 * always realizes the target schema first (unless {@code dryRun}), so a completely empty target
 * needs no pre-boot step by the operator.
 */
@RestController
@RequestMapping("/api/v1/admin/schema")
public class SchemaPromotionController {

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final RuntimeContextService runtimeContextService;

    public SchemaPromotionController(
            ObjectProvider<DataSource> dataSourceProvider,
            RuntimeContextService runtimeContextService
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.runtimeContextService = runtimeContextService;
    }

    public record PromoteRequest(String targetJdbcUrl, String user, String password, boolean dryRun) {
    }

    /**
     * {@code dryRun=true}: read-only preview only (source/target row counts, type-mapping notes) --
     * writes nothing, matching {@code PromoteMain}'s own {@code --dry-run}. {@code dryRun=false}:
     * realizes the target schema, copies every business table, and verifies -- the SAME
     * {@link PromotionArc#realizeAndPromote} arc the CLI runs.
     */
    @PostMapping("/promote")
    public ResponseEntity<Map<String, Object>> promote(
            @RequestBody PromoteRequest request, HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        if (request == null || request.targetJdbcUrl() == null || request.targetJdbcUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetJdbcUrl is required");
        }
        DataSource source = requireDataSource();
        SchemaLifecycleExecutor.SchemaManifest manifest = requireManifest();
        try (CloseableDataSource target = buildTargetDataSource(request)) {
            if (request.dryRun()) {
                CrossEngineDataPromotion.Preview preview =
                        CrossEngineDataPromotion.preview(source, target.dataSource(), manifest);
                return ResponseEntity.ok(toResponseBody(preview));
            }
            // Same dialect assumption PromoteMain's own class javadoc names: the source dialect is
            // THIS running app's own home dialect, active for its whole process (matching the
            // existing bare /promote/apply endpoint); the target's is derived from its connection,
            // never trusted from the manifest (the manifest describes the SOURCE app).
            SqlDialect sourceDialect = SqlDialects.active();
            SqlDialect targetDialect = resolveTargetDialect(target.dataSource());
            PromotionArc.Result result = PromotionArc.realizeAndPromote(
                    source, target.dataSource(), manifest, sourceDialect, targetDialect);
            Map<String, Object> body = toResponseBody(result);
            return result.succeeded() ? ResponseEntity.ok(body) : ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (ResponseStatusException alreadyShaped) {
            throw alreadyShaped;
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "promotion failed: " + failure.getMessage(), failure);
        }
    }

    private static SqlDialect resolveTargetDialect(DataSource target) {
        try (Connection probe = target.getConnection()) {
            return SqlDialects.forConnection(probe);
        } catch (SQLException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "could not connect to target: " + failure.getMessage());
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

    private static CloseableDataSource buildTargetDataSource(PromoteRequest request) {
        DataSource dataSource = DataSourceBuilder.create()
                .url(request.targetJdbcUrl())
                .username(request.user())
                .password(request.password())
                .build();
        AutoCloseable closeable = dataSource instanceof AutoCloseable autoCloseable ? autoCloseable : () -> { };
        return new CloseableDataSource(dataSource, closeable);
    }

    private static Map<String, Object> toResponseBody(CrossEngineDataPromotion.Preview preview) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tableCounts", preview.tableCounts().stream().map(count -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", count.table());
            row.put("sourceRowCount", count.sourceRowCount());
            row.put("targetRowCountBefore", count.targetRowCountBefore());
            return row;
        }).toList());
        body.put("typeMappingNotes", preview.notes().stream().map(note -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", note.table());
            row.put("column", note.column());
            row.put("sqlType", note.sqlType());
            row.put("note", note.note());
            return row;
        }).toList());
        return body;
    }

    private static Map<String, Object> toResponseBody(PromotionArc.Result result) {
        Map<String, Object> body = toResponseBody(result.preview());
        body.put("apply", toResponseBody(result.applyResult()));
        body.put("verify", result.verifyResult().map(SchemaPromotionController::toResponseBody).orElse(null));
        body.put("succeeded", result.succeeded());
        return body;
    }

    private static Map<String, Object> toResponseBody(CrossEngineDataPromotion.PromotionResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tables", result.tables().stream().map(table -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", table.table());
            row.put("sourceRowCount", table.sourceRowCount());
            row.put("rowsCopied", table.rowsCopied());
            row.put("targetRowCountAfter", table.targetRowCountAfter());
            row.put("matched", table.matched());
            row.put("error", table.error());
            return row;
        }).toList());
        body.put("allMatched", result.allMatched());
        return body;
    }

    private static Map<String, Object> toResponseBody(PromotionVerifier.VerificationResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tables", result.tables().stream().map(table -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", table.table());
            row.put("verified", table.verified());
            row.put("rowCountMatches", table.rowCountMatches());
            row.put("contentHashMatches", table.contentHashMatches());
            row.put("nullCountsMatch", table.nullCountsMatch());
            row.put("shapeProblems", table.shapeProblems());
            row.put("error", table.error());
            return row;
        }).toList());
        body.put("allVerified", result.allVerified());
        return body;
    }

    /** Closed after the call whether it succeeds or fails -- never left open across requests. */
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
}
