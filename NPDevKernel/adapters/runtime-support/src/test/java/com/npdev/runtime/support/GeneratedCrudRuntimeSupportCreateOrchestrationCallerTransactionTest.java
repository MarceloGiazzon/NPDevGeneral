package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.runtime.support.crud.orchestration.EventCreateOrchestration;
import com.npdev.runtime.support.crud.orchestration.OrchestrationActionExecutionResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CI hang of 2026-09-30 (CanonicalDemoBusinessE2EIT on Postgres): a lifecycle event published
 * inside AppointmentServiceBase.update's @Transactional ran a create orchestration whose INSERT took
 * its OWN pooled connection, so its FK check waited on the caller's still-open row lock while the
 * caller waited on the insert -- forever, since Postgres cannot see a cycle that closes in Java.
 * These pin the fix: inside a Spring transaction the insert joins the caller's connection (so it
 * commits and rolls back WITH the caller), under a savepoint (so its own failure does not doom the
 * caller); with no transaction it still commits on its own.
 */
class GeneratedCrudRuntimeSupportCreateOrchestrationCallerTransactionTest {

    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private GeneratedCrudRuntimeSupport support;
    private UUID appointmentId;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        // LOCK_TIMEOUT keeps a regression (a second connection blocked on the caller's lock) a fast
        // failure here instead of a hang.
        dataSource.setURL("jdbc:h2:mem:orchestration_caller_tx_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE appointments (id UUID PRIMARY KEY, status VARCHAR(40))");
        jdbc.execute("""
                CREATE TABLE insurance_claims (
                  id UUID PRIMARY KEY,
                  appointment_id UUID NOT NULL REFERENCES appointments(id),
                  status VARCHAR(40) NOT NULL
                )
                """);
        appointmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, status) VALUES (?, 'SCHEDULED')", appointmentId);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        support = new GeneratedCrudRuntimeSupport(
                () -> new CompiledModel("demo", "1.0.0", "v1", Map.of()), kernelRunner(), null, null, null, dataSource);
    }

    @Test
    void insideACallerTransactionTheInsertJoinsItAndRollsBackWithIt() {
        OrchestrationActionExecutionResult result = transactions.execute(status -> {
            // The caller's own write, still uncommitted -- the row lock the old code deadlocked on.
            jdbc.update("UPDATE appointments SET id = id, status = 'COMPLETED' WHERE id = ?", appointmentId);
            OrchestrationActionExecutionResult inner = runCreate(claimAction(true));
            assertEquals(1, claimCount(), "the joined insert must be visible inside the caller's transaction");
            status.setRollbackOnly();
            return inner;
        });

        assertTrue(result.success(), "create must succeed, not time out on the caller's lock: " + result.reason());
        assertEquals("created", result.reason());
        assertEquals(0, claimCount(), "the claim must roll back with the caller, proving it shared its transaction");
    }

    @Test
    void aFailedInsertRollsBackOnlyToItsSavepointAndTheCallerStillCommits() {
        OrchestrationActionExecutionResult result = transactions.execute(status -> {
            jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = ?", appointmentId);
            return runCreate(claimAction(false));
        });

        assertFalse(result.success());
        assertEquals("required_field_missing", result.reason());
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId),
                "the caller's own write must still commit after the orchestration insert failed");
        assertEquals(0, claimCount());
    }

    @Test
    void aTransactionBoundToADifferentDataSourceIsNotJoined() {
        JdbcDataSource otherDataSource = new JdbcDataSource();
        otherDataSource.setURL("jdbc:h2:mem:orchestration_other_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        OrchestrationActionExecutionResult result = new TransactionTemplate(
                new DataSourceTransactionManager(otherDataSource)).execute(status -> {
                    new JdbcTemplate(otherDataSource).execute("SELECT 1");
                    OrchestrationActionExecutionResult inner = runCreate(claimAction(true));
                    status.setRollbackOnly();
                    return inner;
                });

        assertEquals("created", result.reason());
        assertEquals(1, claimCount(),
                "with no connection bound for THIS DataSource the insert must commit on its own, never be left uncommitted");
    }

    @Test
    void withNoTransactionTheInsertCommitsOnItsOwn() {
        OrchestrationActionExecutionResult result = runCreate(claimAction(true));

        assertEquals("created", result.reason());
        assertEquals(1, claimCount());
    }

    private EventCreateOrchestration claimAction(boolean mapStatus) {
        Map<String, String> fieldMap = mapStatus
                ? Map.of("appointmentId", "$event.appointmentId", "status", "$event.claimStatus")
                : Map.of("appointmentId", "$event.appointmentId");
        return new EventCreateOrchestration(
                "InsuranceClaim",
                "insurance_claims",
                // fieldsByName is keyed by the NORMALIZED (lower-cased) field name.
                Map.of(
                        "id", new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        "appointmentid", new CompiledField("appointmentId", "uuid", "java.util.UUID", false, true, false),
                        "status", new CompiledField("status", "string", "String", false, true, false)
                ),
                fieldMap,
                List.of()
        );
    }

    private OrchestrationActionExecutionResult runCreate(EventCreateOrchestration action) {
        EventEnvelope envelope = EventEnvelope.of("AppointmentCompleted",
                Map.of("appointmentId", appointmentId, "claimStatus", "QUEUED"));
        try {
            Method method = GeneratedCrudRuntimeSupport.class.getDeclaredMethod(
                    "executeCreateOrchestrationAction",
                    EventCreateOrchestration.class, EventEnvelope.class, Map.class);
            method.setAccessible(true);
            return (OrchestrationActionExecutionResult) method.invoke(support, action, envelope, envelope.payload());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int claimCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM insurance_claims", Integer.class);
        return count == null ? 0 : count;
    }

    private static KernelRunner kernelRunner() {
        return new KernelRunner(
                (EventBus) event -> {
                },
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }
        );
    }
}
