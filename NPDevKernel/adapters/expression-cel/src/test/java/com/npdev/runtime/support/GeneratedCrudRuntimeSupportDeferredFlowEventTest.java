package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import com.npdev.kernel.FlowDefinition;
import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.FlowDefinitionProvider;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2.4: the round trip. A {@code scheduleEvent} flow step with a delay writes a real
 * {@code npdev_scheduled_event} row on a real H2 database, and the REAL drain
 * ({@code processDueScheduledEvents}) is what publishes it -- only once {@code due_at} has passed.
 *
 * <p><b>Why this lives here and not in :kernel.</b> The kernel's own tests prove the step hands its
 * envelope to a {@link com.npdev.kernel.ports.DeferredEventScheduler} and publishes nothing inline.
 * They cannot prove the ROW is one the drain can consume, because the writer is this class. The
 * column assertions below deliberately mirror {@code ScheduledEventDrainRunnerIT}'s, against DDL
 * copied verbatim from the generator's emitted {@code V1__npdev_schema_realization.sql}, so a
 * divergence between what a flow step writes and what an orchestration action writes fails here.
 *
 * <p><b>How "only when due" is proven without waiting an hour.</b> There is no injectable clock
 * ({@code KernelRunner.nowEpochMillis()} is {@code System.currentTimeMillis()}), so this follows
 * {@code KernelRunnerLifecycleTest}'s established idiom: drain once against the real future
 * {@code due_at} and assert NOTHING fired, then seed the row's {@code due_at} into the past and
 * drain again. The two drains are identical calls; the only thing that changed is the clock's
 * relationship to the stored {@code due_at}, which is exactly the predicate under test.
 */
class GeneratedCrudRuntimeSupportDeferredFlowEventTest {

    private static final String REMINDER_EVENT = "AppointmentReminderDue";
    private static final long DELAY_SECONDS = 3600L;

    private JdbcDataSource dataSource;
    private RecordingEventInfrastructure events;
    private KernelRunner kernelRunner;
    private GeneratedCrudRuntimeSupport runtimeSupport;

    @BeforeEach
    void setUp() throws Exception {
        // Pinned rather than left to resolve: SqlDialects.active() defaults to postgres, and
        // ScheduledEventSql builds its LIMIT suffix from whatever is active.
        SqlDialects.setActive(H2Dialect.INSTANCE);
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:deferred_flow_event_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1");
        createScheduledEventTable(dataSource);

        events = new RecordingEventInfrastructure();
        kernelRunner = new KernelRunner(
                events,
                (entityName, payload) -> List.of(),
                scheduleReminderFlowProvider(),
                (call, state) -> CapabilityResult.success(null),
                events
        );
        // Constructing this is what binds the scheduler to the runner -- nothing in this test wires
        // them together by hand, because nothing in a generated app does either.
        runtimeSupport = new GeneratedCrudRuntimeSupport(
                new CompiledModel("deferred-flow-event", "1.0.0", "v1", Map.<String, CompiledConcept>of()),
                kernelRunner,
                null,
                null,
                null,
                dataSource
        );
    }

    @AfterEach
    void resetDialect() {
        SqlDialects.resetActiveForTesting();
    }

    @Test
    void aDelayedFlowStepWritesADrainableRowAndFiresOnlyOnceItIsDue() throws Exception {
        long before = System.currentTimeMillis();
        ExecutionResult result = kernelRunner.execute(
                "ScheduleReminder",
                Map.of("appointmentId", "apt-r24", "correlationId", "corr-r24-adapter")
        );
        long after = System.currentTimeMillis();

        assertEquals(ExecutionStatus.OK, result.getStatus());
        assertTrue(events.eventNames().isEmpty(),
                "A delayed scheduleEvent must publish nothing at execution time, got: " + events.eventNames());

        String scheduleId = onlyScheduleId();
        assertEquals("PENDING", readColumn(scheduleId, "status"));
        assertEquals(REMINDER_EVENT, readColumn(scheduleId, "event_name"));
        assertEquals("ScheduleReminder", readColumn(scheduleId, "orchestration_name"),
                "orchestration_name carries the FLOW name for a flow-step schedule");
        assertEquals("0", readColumn(scheduleId, "action_index"));
        assertEquals("flow:ScheduleReminder", readColumn(scheduleId, "source_event_name"),
                "the column is NOT NULL and there is no source event -- the flow: prefix is what keeps"
                        + " the drain's echo of it from reading like an event name");
        assertEquals(result.getExecutionId(), readColumn(scheduleId, "source_event_id"));
        assertEquals("corr-r24-adapter", readColumn(scheduleId, "trigger_correlation_id"));
        assertEquals("0", readColumn(scheduleId, "attempt_count"));
        assertNull(readColumn(scheduleId, "processed_at"));
        assertTrue(readColumn(scheduleId, "payload").contains("apt-r24"),
                "the row must carry the envelope payload the drain will republish");

        long dueAtMillis = readTimestampMillis(scheduleId, "due_at");
        assertTrue(dueAtMillis >= before + (DELAY_SECONDS * 1000L) && dueAtMillis <= after + (DELAY_SECONDS * 1000L),
                "due_at must be now + delay, got " + dueAtMillis);

        // First drain: the row is real, the timer is real, and it must NOT fire.
        Map<String, Object> notYetDue = runtimeSupport.processDueScheduledEvents(Boolean.FALSE, 100);
        assertEquals("no_due_records", notYetDue.get("status"));
        assertEquals("PENDING", readColumn(scheduleId, "status"));
        assertTrue(events.eventNames().isEmpty(),
                "A row an hour from due must not be published, got: " + events.eventNames());

        // Seeded, not slept: move due_at into the past and repeat the identical call.
        makeDue(scheduleId);
        Map<String, Object> drained = runtimeSupport.processDueScheduledEvents(Boolean.FALSE, 100);

        assertEquals("ok", drained.get("status"));
        assertEquals(1, drained.get("processed"));
        assertEquals("PROCESSED", readColumn(scheduleId, "status"));
        assertNotNull(readColumn(scheduleId, "processed_at"), "A processed row must carry processed_at");
        assertEquals("1", readColumn(scheduleId, "attempt_count"));
        assertTrue(events.eventNames().contains(REMINDER_EVENT),
                "The deferred event should now be published, got: " + events.eventNames());
        assertTrue(events.eventNames().contains("OrchestrationScheduleProcessed"),
                "The drain should publish its evidence event, got: " + events.eventNames());
        assertFalse(events.readByCorrelation("corr-r24-adapter").isEmpty(),
                "RUN-10: the drain must publish with the SAME correlation id the event was scheduled with,"
                        + " so a correlated AWAIT_EVENT (matchCorrelation: true) is satisfied");
    }

    /**
     * R2.4: the unique {@code schedule_key} is what makes a re-executed step idempotent. Two runs of
     * the same flow are two different executions and must produce two rows; the same envelope
     * offered twice must not.
     */
    @Test
    void twoExecutionsScheduleTwoRowsAndAReofferedEnvelopeSchedulesNoThird() throws Exception {
        kernelRunner.execute("ScheduleReminder", Map.of("appointmentId", "apt-a", "correlationId", "corr-a"));
        kernelRunner.execute("ScheduleReminder", Map.of("appointmentId", "apt-b", "correlationId", "corr-b"));
        assertEquals(2, rowCount());

        EventEnvelope alreadyScheduled = EventEnvelope.create(
                REMINDER_EVENT,
                Map.of("appointmentId", "apt-c"),
                "corr-c",
                "exec-c",
                "ScheduleReminder",
                0
        );
        assertTrue(runtimeSupport.scheduleDeferredFlowEvent(alreadyScheduled, System.currentTimeMillis() + 60_000L));
        assertEquals(3, rowCount());
        assertTrue(runtimeSupport.scheduleDeferredFlowEvent(alreadyScheduled, System.currentTimeMillis() + 60_000L),
                "A duplicate schedule reports success -- it means the row is already there, not that a"
                        + " second reminder was created");
        assertEquals(3, rowCount(), "The duplicate must not have inserted a fourth row");
    }

    @Test
    void aDeferredScheduleWithoutADataSourceReportsFailureRatherThanPublishingNow() {
        GeneratedCrudRuntimeSupport withoutDataSource = new GeneratedCrudRuntimeSupport(
                new CompiledModel("no-datasource", "1.0.0", "v1", Map.<String, CompiledConcept>of()),
                new KernelRunner(events, (entityName, payload) -> List.of()),
                null,
                null,
                null,
                null
        );
        EventEnvelope envelope = EventEnvelope.create(
                REMINDER_EVENT, Map.of(), "corr-none", "exec-none", "ScheduleReminder", 0);

        assertFalse(withoutDataSource.scheduleDeferredFlowEvent(envelope, System.currentTimeMillis() + 60_000L));
        assertFalse(events.eventNames().contains(REMINDER_EVENT),
                "An unwritable schedule must not degrade into an immediate publish");
    }

    private static FlowDefinitionProvider scheduleReminderFlowProvider() {
        FlowDefinition flow = new FlowDefinition(
                "ScheduleReminder",
                "Appointment",
                List.of(
                        FlowStepDefinition.scheduleEvent(
                                "queue-reminder", REMINDER_EVENT, "$input", Map.of(), DELAY_SECONDS),
                        FlowStepDefinition.returnValue("return-input", "$input")
                )
        );
        return flowName -> "ScheduleReminder".equals(flowName) ? Optional.of(flow) : Optional.empty();
    }

    private String onlyScheduleId() throws Exception {
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id FROM npdev_scheduled_event")) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        assertEquals(1, ids.size(), "expected exactly one scheduled row, got " + ids);
        return ids.get(0);
    }

    private int rowCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM npdev_scheduled_event")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void makeDue(String scheduleId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE npdev_scheduled_event SET due_at = ? WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(60)));
            statement.setString(2, scheduleId);
            statement.executeUpdate();
        }
    }

    private String readColumn(String scheduleId, String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM npdev_scheduled_event WHERE id = ?")) {
            statement.setString(1, scheduleId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Object value = rows.getObject(1);
                return value == null ? null : String.valueOf(value);
            }
        }
    }

    private long readTimestampMillis(String scheduleId, String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM npdev_scheduled_event WHERE id = ?")) {
            statement.setString(1, scheduleId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getTimestamp(1).getTime();
            }
        }
    }

    /**
     * Copied verbatim from the generator's emitted {@code V1__npdev_schema_realization.sql} -- the
     * same copy {@code ScheduledEventDrainRunnerIT} uses -- so the writer under test runs against
     * the column types a real app actually has, not a convenient approximation.
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
                      tenant_id TEXT,
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

    /** KernelRunner's constructors take the bus as the event store when it is one; hence both. */
    private static final class RecordingEventInfrastructure implements EventBus, EventStore {
        private final CopyOnWriteArrayList<EventEnvelope> appended = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
            // append() below is the recording seam; publish is the fan-out that would double-count.
        }

        @Override
        public void append(EventEnvelope event) {
            appended.add(event);
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            return appended.stream()
                    .filter(envelope -> correlationId != null && correlationId.equals(envelope.correlationId()))
                    .toList();
        }

        @Override
        public List<EventEnvelope> readByEventName(String eventName) {
            return appended.stream()
                    .filter(envelope -> eventName != null && eventName.equals(envelope.eventName()))
                    .toList();
        }

        private List<String> eventNames() {
            return appended.stream().map(EventEnvelope::eventName).toList();
        }
    }
}
