package com.finalexec.webhook;

import com.finalexec.config.DefaultTenant;
import com.npdev.adapters.webhook.http.HttpWebhookCapabilityAdapter;
import com.npdev.adapters.webhook.http.WebhookDeliveryRecordStore;
import com.npdev.adapters.webhook.http.WebhookEgressDeniedException;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * INT-5: the {@code webhook} capability served by the platform for a model binding
 * {@code { "capability": "webhook", "adapter": "webhook-http" }}. {@code post} validates the
 * destination (allowlist, HMAC secret present -- fail-closed) and only INSERTS a durable queue row;
 * {@link WebhookDeliveryWorker} sends it in the background with backoff. The caller (a user's save
 * request running an orchestration rule) never waits on the remote.
 */
public final class QueuedWebhookCapabilityHandler implements CapabilityAdapter {

    public static final String ADAPTER_ID = "webhook-http";

    private final Supplier<HttpWebhookCapabilityAdapter> adapter;
    private final Supplier<DataSource> dataSource;
    private final WebhookDeliveryRecordStore store;
    private final AtomicBoolean schemaReady = new AtomicBoolean();

    public QueuedWebhookCapabilityHandler(
            Supplier<HttpWebhookCapabilityAdapter> adapter,
            Supplier<DataSource> dataSource,
            WebhookDeliveryRecordStore store
    ) {
        this.adapter = adapter;
        this.dataSource = dataSource;
        this.store = store;
    }

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public String capability() {
        return "webhook";
    }

    @Override
    public String capabilityType() {
        return "WebhookCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if (!"post".equals(call.operation())) {
            return CapabilityResult.failure("WEBHOOK_OPERATION_UNSUPPORTED",
                    "Unsupported webhook operation: " + call.operation() + " (use 'post')",
                    CapabilityErrorKind.CONTRACT, Map.of("operation", call.operation()));
        }
        HttpWebhookCapabilityAdapter http = adapter.get();
        DataSource source = dataSource.get();
        if (http == null || source == null) {
            return CapabilityResult.failure("WEBHOOK_NOT_CONFIGURED",
                    "The webhook-http adapter or the database is not available in this app",
                    CapabilityErrorKind.PERMANENT, Map.of());
        }
        Object payload = call.args().isEmpty() ? Map.of() : call.args().get(0);
        HttpWebhookCapabilityAdapter.PreparedDelivery prepared;
        try {
            prepared = http.prepare(payload);
        } catch (WebhookEgressDeniedException denied) {
            return CapabilityResult.failure(denied.code(), denied.getMessage(), CapabilityErrorKind.CONTRACT, Map.of());
        } catch (IllegalArgumentException invalid) {
            return CapabilityResult.failure("WEBHOOK_PAYLOAD_INVALID", invalid.getMessage(),
                    CapabilityErrorKind.CONTRACT, Map.of());
        }
        String eventName = eventName(contextState, payload);
        String deliveryId = UUID.randomUUID().toString();
        try (Connection connection = source.getConnection()) {
            if (schemaReady.compareAndSet(false, true)) {
                try {
                    store.ensureSchema(connection);
                } catch (SQLException | RuntimeException failure) {
                    schemaReady.set(false);
                    throw failure;
                }
            }
            store.insertQueued(connection, deliveryId, tenantId(contextState), prepared.host(), prepared.url(),
                    prepared.bodyJson(), eventName, Instant.now());
        } catch (IllegalArgumentException invalid) {
            return CapabilityResult.failure("WEBHOOK_TENANT_INVALID", invalid.getMessage(),
                    CapabilityErrorKind.CONTRACT, Map.of());
        } catch (SQLException failure) {
            return CapabilityResult.failure("WEBHOOK_QUEUE_FAILED", "Could not queue the webhook delivery",
                    CapabilityErrorKind.TRANSIENT, Map.of());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", deliveryId);
        result.put("status", "queued");
        return CapabilityResult.success(result);
    }

    /** The calling tenant (orchestration passes the event's tenant); the home tenant when the caller carries none. */
    private static String tenantId(Map<String, Object> contextState) {
        Object fromContext = contextState == null ? null : contextState.get("tenantId");
        return DefaultTenant.orDefault(fromContext == null ? null : String.valueOf(fromContext));
    }

    private static String eventName(Map<String, Object> contextState, Object payload) {
        Object fromContext = contextState == null ? null : contextState.get("eventName");
        if (fromContext != null && !String.valueOf(fromContext).isBlank()) {
            return String.valueOf(fromContext);
        }
        if (payload instanceof Map<?, ?> map && map.get("event") != null && !String.valueOf(map.get("event")).isBlank()) {
            return String.valueOf(map.get("event"));
        }
        return null;
    }
}
