package com.npdev.adapters.runtime.validation;

import com.npdev.kernel.ports.EventStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public final class NpdevEventStoreHealthIndicator implements HealthIndicator {
    private final RuntimeSettings settings;
    private final EventStore eventStore;

    public NpdevEventStoreHealthIndicator(RuntimeSettings settings, EventStore eventStore) {
        this.settings = settings;
        this.eventStore = eventStore;
    }

    @Override
    public Health health() {
        if (eventStore == null) {
            return Health.down().withDetail("reason", "event_store_missing").build();
        }
        try {
            String tenant = settings == null || settings.isPostgresMode() ? "health" : "default";
            eventStore.readByCorrelation("npdev_health_check", tenant);
            return Health.up()
                    .withDetail("storeType", eventStore.getClass().getSimpleName())
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("storeType", eventStore.getClass().getSimpleName())
                    .build();
        }
    }
}
