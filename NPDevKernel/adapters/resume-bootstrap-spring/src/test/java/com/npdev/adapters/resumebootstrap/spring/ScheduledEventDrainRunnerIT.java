package com.npdev.adapters.resumebootstrap.spring;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The R2.3 definition of done: a booted context with a due scheduled event fires it within one tick
 * with <b>no REST poke</b>.
 *
 * <p>Everything below the runner is production code -- a real {@code npdev_scheduled_event} table on
 * a real H2 database, drained by the real {@code GeneratedCrudRuntimeSupport.processDueScheduledEvents}
 * through the real {@link com.npdev.kernel.KernelRunner} event path. The only test-owned pieces are
 * the DDL (copied verbatim from the generator's emitted
 * {@code V1__npdev_schema_realization.sql}) and a recording event store.
 *
 * <p><b>What makes this a proof rather than a demonstration.</b> The test never calls
 * {@code processDueScheduledEvents}; it asserts the call arrived on a Spring scheduler thread, not
 * the JUnit thread, so the only thing that could have fired it is the timer. And it plants a SECOND
 * row due an hour from now: if the drain were firing rows indiscriminately (i.e. passing
 * {@code forceDue=true}) that row would fire too, so its staying PENDING is what pins {@code due_at}
 * being honoured.
 *
 * <p>Engine: H2 only. Postgres/MySQL/SQL Server are exercised for this table by the dialect
 * conformance suite; this test is about the timer, not the SQL.
 */
class ScheduledEventDrainRunnerIT {

    private static final String DUE_EVENT_NAME = "ContactFollowUpDue";
    private static final String FUTURE_EVENT_NAME = "ContactFollowUpLater";
    private static final Duration DRAIN_DEADLINE = Duration.ofSeconds(15);
    private static final long FAST_TICK_MILLIS = 50L;

    private JdbcDataSource dataSource;

    @BeforeEach
    void pinH2Dialect() throws Exception {
        // Pinned rather than left to resolve: SqlDialects.active() defaults to postgres, and
        // ScheduledEventSql builds its LIMIT suffix from whatever is active.
        SqlDialects.setActive(H2Dialect.INSTANCE);
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:scheduled_event_drain_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        createScheduledEventTable(dataSource);
    }

    @AfterEach
    void resetDialect() {
        SqlDialects.resetActiveForTesting();
    }

    @Test
    void aDueScheduledEventFiresFromTheTimerWithoutAnyRestPoke() throws Exception {
        UUID dueId = UUID.randomUUID();
        UUID futureId = UUID.randomUUID();
        insertPendingSchedule(dueId, DUE_EVENT_NAME, Instant.now().minusSeconds(60));
        insertPendingSchedule(futureId, FUTURE_EVENT_NAME, Instant.now().plusSeconds(3600));

        RecordingEventStore events = new RecordingEventStore();
        GeneratedCrudRuntimeSupport runtimeSupport = new GeneratedCrudRuntimeSupport(
                new CompiledModel("drain-it", "1.0.0", "v1", Map.<String, CompiledConcept>of()),
                new KernelRunner(events, (entityName, payload) -> List.of()),
                null,
                null,
                null,
                dataSource
        );

        AtomicReference<String> firstDrainThread = new AtomicReference<>();
        AtomicReference<Boolean> firstForceDue = new AtomicReference<>();
        AtomicInteger drainCalls = new AtomicInteger();
        ScheduledEventDrainRunner runner = new ScheduledEventDrainRunner(
                (forceDue, limit) -> {
                    drainCalls.incrementAndGet();
                    firstDrainThread.compareAndSet(null, Thread.currentThread().getName());
                    firstForceDue.compareAndSet(null, forceDue);
                    return runtimeSupport.processDueScheduledEvents(forceDue, limit);
                },
                100,
                true
        );

        String testThread = Thread.currentThread().getName();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "scheduled-event-drain-it",
                    Map.of("npdev.scheduler.tick-millis", String.valueOf(FAST_TICK_MILLIS))
            ));
            context.registerBean(PropertySourcesPlaceholderConfigurer.class,
                    PropertySourcesPlaceholderConfigurer::new);
            context.register(SchedulingContext.class);
            context.registerBean(ScheduledEventDrainRunner.class, () -> runner);
            context.refresh();

            assertTrue(awaitStatus(dueId, "PROCESSED"),
                    "The due scheduled event should have been drained by the timer within "
                            + DRAIN_DEADLINE.toSeconds() + "s at a " + FAST_TICK_MILLIS + "ms tick, but it is still "
                            + readColumn(dueId, "status"));
        }

        assertTrue(drainCalls.get() > 0, "The scheduler should have invoked the drain at least once");
        assertNotEquals(testThread, firstDrainThread.get(),
                "The drain must have run on a scheduler thread -- if it ran on the JUnit thread the "
                        + "test poked it, which is exactly what this test exists to rule out");
        assertEquals(Boolean.FALSE, firstForceDue.get(),
                "The timer must pass forceDue=false so due_at is honoured; forceDue=true is the "
                        + "operator-only REST escape hatch");

        assertEquals("PROCESSED", readColumn(dueId, "status"));
        assertNotNull(readColumn(dueId, "processed_at"), "A processed row must carry processed_at");
        assertEquals("1", readColumn(dueId, "attempt_count"));

        assertEquals("PENDING", readColumn(futureId, "status"),
                "A row due in an hour must NOT be drained -- that would mean due_at is being ignored");

        List<String> firedEventNames = events.eventNames();
        assertTrue(firedEventNames.contains(DUE_EVENT_NAME),
                "The scheduled event itself should have been published, got: " + firedEventNames);
        assertTrue(firedEventNames.contains("OrchestrationScheduleProcessed"),
                "The drain should have published its evidence event, got: " + firedEventNames);
        assertFalse(firedEventNames.contains(FUTURE_EVENT_NAME),
                "The not-yet-due event must not have been published, got: " + firedEventNames);
    }

    @Configuration
    @EnableScheduling
    static class SchedulingContext {
    }

    private boolean awaitStatus(UUID scheduleId, String expectedStatus) throws Exception {
        long deadline = System.nanoTime() + DRAIN_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            if (expectedStatus.equals(readColumn(scheduleId, "status"))) {
                return true;
            }
            Thread.sleep(FAST_TICK_MILLIS);
        }
        return false;
    }

    private String readColumn(UUID scheduleId, String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM npdev_scheduled_event WHERE id = ?")) {
            statement.setString(1, scheduleId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Object value = rows.getObject(1);
                return value == null ? null : String.valueOf(value);
            }
        }
    }

    private void insertPendingSchedule(UUID scheduleId, String eventName, Instant dueAt) throws Exception {
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO npdev_scheduled_event (
                       id, schedule_key, orchestration_name, action_index, source_event_name,
                       source_event_id, trigger_correlation_id, event_name, due_at, payload,
                       status, attempt_count, created_at, updated_at
                     ) VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                     """)) {
            statement.setString(1, scheduleId.toString());
            statement.setString(2, "drain-it:" + scheduleId);
            statement.setString(3, "ContactFollowUp");
            statement.setString(4, "ContactCreated");
            statement.setString(5, UUID.randomUUID().toString());
            statement.setString(6, UUID.randomUUID().toString());
            statement.setString(7, eventName);
            statement.setTimestamp(8, Timestamp.from(dueAt));
            statement.setString(9, "{\"contactId\":\"" + scheduleId + "\"}");
            statement.setTimestamp(10, now);
            statement.setTimestamp(11, now);
            statement.executeUpdate();
        }
    }

    /**
     * Copied verbatim from the generator's emitted {@code V1__npdev_schema_realization.sql} so the
     * drain runs against the column types a real app actually has, not a convenient approximation.
     */
    private static void createScheduledEventTable(JdbcDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npdev_scheduled_event (
                      id VARCHAR(191) NOT NULL,
                      schedule_key VARCHAR(191) NOT NULL,
                      orchestration_name TEXT NOT NULL,
                      action_index INTEGER NOT NULL,
                      source_event_name VARCHAR(191) NOT NULL,
                      source_event_id VARCHAR(191),
                      trigger_correlation_id TEXT,
                      event_name TEXT NOT NULL,
                      due_at TIMESTAMP NOT NULL,
                      payload TEXT NOT NULL,
                      status VARCHAR(191) NOT NULL DEFAULT 'PENDING',
                      attempt_count INTEGER NOT NULL DEFAULT 0,
                      created_at TIMESTAMP NOT NULL,
                      updated_at TIMESTAMP NOT NULL,
                      processed_at TIMESTAMP,
                      PRIMARY KEY (id)
                    )
                    """);
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_npdev_scheduled_event_schedule_key "
                    + "ON npdev_scheduled_event (schedule_key)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_npdev_scheduled_event_status_due "
                    + "ON npdev_scheduled_event (status, due_at)");
        }
    }

    /**
     * KernelRunner's two-argument constructor uses the bus as its event store when the bus happens to
     * be one, which is why this implements both rather than being two objects.
     */
    private static final class RecordingEventStore implements EventBus, EventStore {
        private final CopyOnWriteArrayList<EventEnvelope> appended = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
            // The store's append() below is the recording seam; publish is the fan-out the kernel
            // does afterwards and would double-count here.
        }

        @Override
        public void append(EventEnvelope event) {
            appended.add(event);
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            List<EventEnvelope> out = new ArrayList<>();
            for (EventEnvelope envelope : appended) {
                if (envelope.correlationId() != null && envelope.correlationId().equals(correlationId)) {
                    out.add(envelope);
                }
            }
            return List.copyOf(out);
        }

        @Override
        public List<EventEnvelope> readByEventName(String eventName) {
            List<EventEnvelope> out = new ArrayList<>();
            for (EventEnvelope envelope : appended) {
                if (envelope.eventName() != null && envelope.eventName().equals(eventName)) {
                    out.add(envelope);
                }
            }
            return List.copyOf(out);
        }

        private List<String> eventNames() {
            List<String> names = new ArrayList<>();
            for (EventEnvelope envelope : appended) {
                names.add(envelope.eventName());
            }
            return List.copyOf(names);
        }
    }
}
