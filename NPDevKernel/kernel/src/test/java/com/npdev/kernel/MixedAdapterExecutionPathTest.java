package com.npdev.kernel;

import com.npdev.adapters.events.inproc.InProcEventStore;
import com.npdev.adapters.persistence.postgres.PostgresPersistenceCapabilityAdapter;
import com.npdev.adapters.tracing.inproc.InProcExecutionTracer;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixedAdapterExecutionPathTest {

    @Test
    void persistencePostgresEventsInProcAndTracingInProcStayCoherentOnOneCorrelationPath() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:mixed_adapter_execution;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("sa");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE contacts (id UUID PRIMARY KEY, email VARCHAR(255), name VARCHAR(255))");
        }

        PostgresPersistenceCapabilityAdapter persistence = new PostgresPersistenceCapabilityAdapter(dataSource);
        InProcEventStore eventStore = new InProcEventStore();
        InProcExecutionTracer tracer = new InProcExecutionTracer();

        String recordId = UUID.randomUUID().toString();
        String executionId = "mixed-exec-" + UUID.randomUUID();
        String correlationId = "mixed-corr-" + UUID.randomUUID();
        long startedAt = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) persistence.save(
                "contact",
                Map.of(
                        "id", recordId,
                        "email", "mixed@example.test",
                        "name", "Mixed Path"
                )
        );
        assertEquals("mixed@example.test", saved.get("email"));

        EventEnvelope event = EventEnvelope.of(
                "ContactSaved",
                Map.of("id", recordId, "adapterId", "events-inproc"),
                correlationId,
                executionId,
                "MixedAdapterFlow",
                0,
                Map.of("adapterId", "events-inproc"),
                "tenant-a",
                "actor-a"
        );
        eventStore.append(event);

        FlowTraceMeta meta = new FlowTraceMeta(
                executionId,
                correlationId,
                "MixedAdapterFlow",
                "tenant-a",
                "actor-a",
                Map.of(
                        "persistenceAdapter", "persistence-postgres",
                        "eventAdapter", "events-inproc",
                        "tracingAdapter", "tracing-inproc"
                )
        );
        tracer.onFlowStart(meta, startedAt);
        tracer.onStepStart(meta, 0, "PersistContact", "capability", startedAt);

        StepTrace stepTrace = new StepTrace(
                0,
                "PersistContact",
                "capability",
                startedAt,
                startedAt + 1,
                StepOutcome.OK,
                Map.of(
                        "persistenceAdapter", "persistence-postgres",
                        "eventAdapter", "events-inproc",
                        "tracingAdapter", "tracing-inproc"
                ),
                List.of(),
                null
        );
        tracer.onStepEnd(meta, stepTrace);
        tracer.onFlowEnd(new FlowTrace(meta, startedAt, startedAt + 2, StepOutcome.OK, List.of(stepTrace)));

        @SuppressWarnings("unchecked")
        Map<String, Object> persisted = (Map<String, Object>) persistence.findById("contact", recordId);
        assertEquals("mixed@example.test", persisted.get("email"));
        assertEquals("Mixed Path", persisted.get("name"));

        EventEnvelope storedEvent = eventStore.findFirst("ContactSaved", correlationId).orElseThrow();
        assertEquals(correlationId, storedEvent.correlationId());
        assertEquals("events-inproc", ((Map<?, ?>) storedEvent.payload().get("_meta")).get("adapterId"));

        FlowTrace storedTrace = tracer.findByExecutionId(executionId).orElseThrow();
        assertEquals(correlationId, storedTrace.meta().correlationId());
        assertEquals("persistence-postgres", storedTrace.meta().tags().get("persistenceAdapter"));
        assertEquals("events-inproc", storedTrace.meta().tags().get("eventAdapter"));
        assertEquals("tracing-inproc", storedTrace.meta().tags().get("tracingAdapter"));
        assertEquals(StepOutcome.OK, storedTrace.outcome());
        assertTrue(storedTrace.steps().stream().anyMatch(step -> "PersistContact".equals(step.stepName())));
    }
}
