package com.finalexec.api;

import com.npdev.adapters.webhook.http.HttpWebhookCapabilityAdapter;
import com.npdev.adapters.webhook.http.WebhookDeliveryRecordStore;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * INT-5: operator view of the outbound webhook queue (see docs/reference/OUTBOUND_WEBHOOKS.md). ADMIN-gated.
 * The list never carries payloads (they may hold personal data); only {@code /{id}/payload} does.
 * 503 when the model does not bind adapter {@code webhook-http}.
 */
@RestController
@RequestMapping("/api/admin/webhook-deliveries")
public class WebhookDeliveryAdminController {

    private static final Set<String> STATUSES = Set.of(
            WebhookDeliveryRecordStore.STATUS_PENDING, WebhookDeliveryRecordStore.STATUS_SENDING,
            WebhookDeliveryRecordStore.STATUS_DELIVERED, WebhookDeliveryRecordStore.STATUS_FAILED);

    private final ObjectProvider<HttpWebhookCapabilityAdapter> adapter;
    private final ObjectProvider<DataSource> dataSource;
    private final WebhookDeliveryRecordStore store;
    private final RuntimeContextService runtimeContextService;

    public WebhookDeliveryAdminController(
            ObjectProvider<HttpWebhookCapabilityAdapter> adapter,
            ObjectProvider<DataSource> dataSource,
            WebhookDeliveryRecordStore store,
            RuntimeContextService runtimeContextService
    ) {
        this.adapter = adapter;
        this.dataSource = dataSource;
        this.store = store;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        String tenantId = requireAdmin(request);
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + STATUSES);
        }
        int bounded = Math.max(1, Math.min(limit, 200));
        try (Connection connection = open()) {
            store.ensureSchema(connection);
            return store.list(connection, tenantId, normalized, bounded);
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    @GetMapping("/{id}/payload")
    public String payload(HttpServletRequest request, @PathVariable String id) {
        String tenantId = requireAdmin(request);
        try (Connection connection = open()) {
            store.ensureSchema(connection);
            return store.payload(connection, tenantId, id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown delivery"));
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    @PostMapping("/{id}/redeliver")
    public Map<String, Object> redeliver(HttpServletRequest request, @PathVariable String id) {
        String tenantId = requireAdmin(request);
        try (Connection connection = open()) {
            store.ensureSchema(connection);
            if (store.redeliver(connection, tenantId, id, Instant.now())) {
                return Map.of("id", id, "status", WebhookDeliveryRecordStore.STATUS_PENDING);
            }
            if (store.payload(connection, tenantId, id).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown delivery");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "delivery is already queued or being sent");
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private Connection open() throws SQLException {
        DataSource source = dataSource.getIfAvailable();
        if (adapter.getIfAvailable() == null || source == null) {
            throw unavailable();
        }
        return source.getConnection();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "webhook-http is not bound by this app's model, or the delivery store is unavailable");
    }

    /** Returns the caller's tenant: an admin sees and replays only their own tenant's deliveries. */
    private String requireAdmin(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.roles().contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return context.tenantId();
    }
}
