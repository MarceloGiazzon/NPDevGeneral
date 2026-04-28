package com.npdev.adapters.runtime.validation;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.runtime.SchedulerRuntimeState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeHealthIndicatorsTest {
    // PluginRegistryHealthIndicator
    // CapabilityDispatcherHealthIndicator
    // MigrationStatusHealthIndicator
    // UP / DOWN / UNKNOWN detail coverage
    // health indicator performance target <50ms per indicator

    @Test
    void schedulerHealthShouldBeUpWhenTickIsRecent() {
        RuntimeSettings settings = new RuntimeSettings(
                "inproc",
                true,
                100,
                2000,
                true,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.markSuccess(System.currentTimeMillis());

        NpdevSchedulerHealthIndicator indicator = new NpdevSchedulerHealthIndicator(settings, runtimeState);

        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void dbHealthShouldBeUpWhenInprocMode() {
        RuntimeSettings settings = new RuntimeSettings(
                "inproc",
                false,
                100,
                2000,
                true,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );

        NpdevDbHealthIndicator indicator = new NpdevDbHealthIndicator(settings, null);

        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void eventHealthShouldBeUpWhenStoreReadable() {
        RuntimeSettings settings = new RuntimeSettings(
                "inproc",
                false,
                100,
                2000,
                true,
                1024,
                64,
                null,
                null,
                null,
                5,
                30,
                10,
                4096,
                null
        );

        EventStore store = new EventStore() {
            @Override
            public void append(EventEnvelope event) {
            }

            @Override
            public List<EventEnvelope> readByCorrelation(String correlationId) {
                return List.of();
            }

            @Override
            public List<EventEnvelope> readByEventName(String eventName) {
                return List.of();
            }
        };

        NpdevEventStoreHealthIndicator indicator = new NpdevEventStoreHealthIndicator(settings, store);

        assertEquals(Status.UP, indicator.health().getStatus());
    }
}
