package com.npdev.adapters.runtime.validation;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.runtime.SchedulerRuntimeState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
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
                null,
                true
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
                null,
                true
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
                null,
                true
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

    // W3.3 (2026-08-25 remediation plan / QUAL-33): the "when a dependency is DOWN" half of this
    // file's own listed aspiration ("UP / DOWN / UNKNOWN detail coverage"), added while reviving
    // RuntimeHealthEndpointIT. A @SpringBootTest-based version of this test (substituting a broken
    // EventStore bean and reading the real /actuator/health response) was tried first and abandoned:
    // its @Primary EventStore override leaked into an UNRELATED @SpringBootTest class running in the
    // same Gradle test JVM (both showed the identical test-double exception, despite distinct Spring
    // Boot startup banners -- genuinely separate ApplicationContext instances, not context-cache
    // reuse). Neither @Import(explicit) nor @DirtiesContext fixed it. This plain unit test proves the
    // same real logic -- NpdevEventStoreHealthIndicator.health() reports DOWN when the EventStore
    // dependency is DOWN -- with no Spring context at all, so there is nothing to leak.
    @Test
    void eventHealthShouldBeDownWhenStoreDependencyIsDown() {
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
                null,
                true
        );

        EventStore store = new EventStore() {
            @Override
            public void append(EventEnvelope event) {
                throw new IllegalStateException("event store dependency is DOWN (test double)");
            }

            @Override
            public List<EventEnvelope> readByCorrelation(String correlationId) {
                throw new IllegalStateException("event store dependency is DOWN (test double)");
            }

            @Override
            public List<EventEnvelope> readByEventName(String eventName) {
                throw new IllegalStateException("event store dependency is DOWN (test double)");
            }
        };

        NpdevEventStoreHealthIndicator indicator = new NpdevEventStoreHealthIndicator(settings, store);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(
                "java.lang.IllegalStateException: event store dependency is DOWN (test double)",
                String.valueOf(health.getDetails().get("error"))
        );
    }
}
