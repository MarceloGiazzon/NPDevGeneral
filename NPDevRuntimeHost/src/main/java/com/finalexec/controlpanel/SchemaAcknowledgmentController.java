package com.finalexec.controlpanel;

import com.finalexec.db.PendingSchemaAcknowledgmentStore;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
