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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * REG-232, integration half: reproduces the real bug end to end through
 * {@code executeCreateOrchestrationAction} itself (not just the {@code isNotNullViolation}
 * classifier {@link GeneratedCrudRuntimeSupportNotNullViolationTest} covers in isolation) --
 * exactly the shape that shipped unnoticed on wmsoffice-browser: an orchestration create action
 * whose field mapping never mentions a NOT NULL target column (the model never bound it, mirroring
 * {@code historico_movimentacaos.movimento_id}), so the INSERT omits that column entirely and H2
 * rejects the row. Before the fix this returned {@code create_failed} with a full JDBC stack trace
 * logged at WARNING; after it, {@code required_field_missing} at INFO with just the message.
 */
class GeneratedCrudRuntimeSupportCreateOrchestrationNotNullTest {

    @Test
    void aTargetColumnMissingFromTheFieldMappingIsReportedAsRequiredFieldMissingNotCreateFailed()
            throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:runtime_orchestration_not_null;"
                + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE historico_movimentacaos (
                      id UUID PRIMARY KEY,
                      movimento_id UUID NOT NULL,
                      note VARCHAR(255)
                    )
                    """);
        }

        GeneratedCrudRuntimeSupport support = new GeneratedCrudRuntimeSupport(
                compiledModel(), kernelRunner(), null, null, null, dataSource);

        // movimentoId/movimento_id deliberately has NO CompiledField and NO fieldMap entry -- the
        // exact real-world shape: the model never bound the history record's own required column.
        EventCreateOrchestration action = new EventCreateOrchestration(
                "HistoricoMovimentacao",
                "historico_movimentacaos",
                Map.of(
                        "id", new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        "note", new CompiledField("note", "string", "String", false, false, false)
                ),
                Map.of("note", "$event.sourceNote"),
                List.of()
        );
        EventEnvelope envelope = EventEnvelope.of("RecebimentoUpdated", Map.of("sourceNote", "test"));

        Method method = GeneratedCrudRuntimeSupport.class.getDeclaredMethod(
                "executeCreateOrchestrationAction",
                EventCreateOrchestration.class, EventEnvelope.class, Map.class);
        method.setAccessible(true);
        OrchestrationActionExecutionResult result = (OrchestrationActionExecutionResult)
                method.invoke(support, action, envelope, envelope.payload());

        assertFalse(result.success(), "an omitted NOT NULL column must still fail the action");
        assertEquals("required_field_missing", result.reason(),
                "REG-232: must be classified as a clean required-field failure, not the generic "
                        + "'create_failed' a raw, unclassified JDBC exception used to produce");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            var rows = statement.executeQuery("SELECT COUNT(*) FROM historico_movimentacaos");
            rows.next();
            assertEquals(0, rows.getInt(1), "the rejected insert must leave no partial row behind");
        }
    }

    private static CompiledModel compiledModel() {
        return new CompiledModel("demo", "1.0.0", "v1", Map.of());
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
