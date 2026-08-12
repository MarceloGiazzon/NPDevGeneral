package com.finalexec.controlpanel;

import com.finalexec.db.ExpressionBackfillPreview;
import com.finalexec.db.ImpactReportJson;
import com.finalexec.db.SchemaImpactFacade;
import com.finalexec.db.SchemaLifecycleExecutor;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SER-P6.5 (Surface 3). ControlPanel surface for the Impact Report: an on-demand, read-only view of
 * what the CURRENTLY RUNNING app's model would do to its CURRENTLY LIVE database, computed via the
 * same {@link SchemaImpactFacade#forLiveDatabase} entry point the {@code -ImpactOnly} CLI (Surface 2,
 * SER-P6.4) uses — so the two surfaces can never disagree.
 *
 * <p>SUPERUSER-gated, following {@link SchemaAcknowledgmentController}'s exact pattern (manual
 * {@code hasRole("SUPERUSER")} check via {@link RuntimeContextService}, not an annotation). Mapped at
 * the same {@code /api/admin/schema-migration} base (confirmed not to collide: distinct sub-paths
 * {@code /impact} and {@code /impact/view}).
 */
@RestController
@RequestMapping("/api/admin/schema-migration")
public class SchemaImpactController {

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final RuntimeContextService runtimeContextService;
    private final CompiledModel compiledModel;

    public SchemaImpactController(
            ObjectProvider<DataSource> dataSourceProvider,
            RuntimeContextService runtimeContextService,
            CompiledModel compiledModel
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.runtimeContextService = runtimeContextService;
        this.compiledModel = compiledModel;
    }

    @GetMapping(value = "/impact", produces = "application/json")
    public ResponseEntity<String> impact(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();
        // REG-39 layer 3: same identity-pack-drift check StartupValidator fails fast on at boot,
        // surfaced here too so it's visible without a boot (NEEDS_ATTENTION item if stale).
        SchemaImpactFacade.Result r = SchemaImpactFacade.forLiveDatabase(dataSource, compiledModel);
        String json = ImpactReportJson.render(r.report(), Instant.now().toString(),
                r.fromFingerprint(), r.toFingerprint(), r.ackToken(), r.surplus());
        return ResponseEntity.ok().header("Content-Type", "application/json").body(json);
    }

    /**
     * Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): a dry-run preview of every pending
     * expression-default backfill against the CURRENTLY LIVE database -- rows affected, distinct
     * resulting values, and rows where evaluation produces no value (blocking application). Writes
     * nothing. {@code ackToken}, when items are present, is the SAME token an operator submits via
     * the EXISTING {@code POST /acknowledge} endpoint (no new acknowledgment channel) to authorize
     * {@code BackfillPass} applying them on this app's next boot.
     */
    @GetMapping(value = "/expression-backfill-preview", produces = "application/json")
    public Map<String, Object> expressionBackfillPreview(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        DataSource dataSource = requireDataSource();
        SchemaLifecycleExecutor.SchemaManifest manifest = SchemaLifecycleExecutor.loadManifest();
        Map<String, Object> body = new LinkedHashMap<>();
        if (manifest == null || !manifest.physicalDatabase()) {
            body.put("items", List.of());
            body.put("ackToken", null);
            return body;
        }
        List<ExpressionBackfillPreview.Item> items = ExpressionBackfillPreview.preview(dataSource, manifest);
        List<Map<String, Object>> encoded = items.stream().map(item -> {
            Map<String, Object> encodedItem = new LinkedHashMap<>();
            encodedItem.put("table", item.table());
            encodedItem.put("column", item.column());
            encodedItem.put("expression", item.expression());
            encodedItem.put("rowsAffected", item.rowsAffected());
            encodedItem.put("distinctValues", item.distinctValues());
            encodedItem.put("failedRowIds", item.failedRowIds());
            return encodedItem;
        }).toList();
        body.put("items", encoded);
        body.put("ackToken", items.isEmpty() ? null : ExpressionBackfillPreview.expectedToken(manifest.schemaFingerprint(), items));
        body.put("toFingerprint", manifest.schemaFingerprint());
        return body;
    }

    /**
     * Minimal, self-contained diagnostic page: no static asset path exists in the RuntimeHost
     * template to serve this from (the static-asset serving path is generator-specific), so it is
     * inline HTML served straight from the controller. Fetches {@link #impact} client-side via the
     * {@code X-Super-User-Key} header the operator supplies at a prompt — the key is never echoed or
     * logged server-side.
     */
    @GetMapping(value = "/impact/view", produces = "text/html")
    public ResponseEntity<String> impactView(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Schema Impact</title>"
            + "<style>body{font:14px/1.5 system-ui,monospace;margin:2rem;max-width:70rem}"
            + "pre{background:#0b1020;color:#d6e2ff;padding:1rem;overflow:auto;border-radius:6px}"
            + ".dz{color:#ff6b6b;font-weight:700}</style></head><body>"
            + "<h1>Schema impact report</h1>"
            + "<p>Read-only. Requires the SUPERUSER key.</p>"
            + "<button id=go>Load impact</button> <span id=st></span><pre id=out>(not loaded)</pre>"
            + "<script>document.getElementById('go').onclick=async()=>{"
            + "const k=prompt('X-Super-User-Key');if(!k)return;"
            + "document.getElementById('st').textContent='loading…';"
            + "const res=await fetch('/api/admin/schema-migration/impact',{headers:{'X-Super-User-Key':k}});"
            + "if(!res.ok){document.getElementById('out').textContent='HTTP '+res.status;return;}"
            + "const j=await res.json();const lines=[];"
            + "lines.push('verdict: '+j.verdict);lines.push('from: '+j.fingerprintFrom+' -> '+j.fingerprintTo);"
            + "if(j.acknowledgmentToken)lines.push('token: '+j.acknowledgmentToken);"
            + "for(const it of (j.items||[]))lines.push((it.safetyClass.startsWith('DESTRUCTIVE')?'!! ':'   ')"
            + "+it.safetyClass+'  '+(it.table||'')+'.'+(it.column||'-')+'  rows='+it.rowsAffected);"
            + "document.getElementById('out').textContent=lines.join('\\n');"
            + "document.getElementById('st').textContent='';};</script></body></html>";
        return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8").body(html);
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
