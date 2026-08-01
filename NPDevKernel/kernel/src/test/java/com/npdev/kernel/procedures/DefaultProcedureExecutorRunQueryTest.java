package com.npdev.kernel.procedures;

import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LIFT-QUERY-P1: a `runQuery` procedure step honors the named query's where/orderBy/limit. */
class DefaultProcedureExecutorRunQueryTest {

    private static final CapabilityDispatcher NOOP_DISPATCHER = (call, state) -> null;
    private static final EventBus NOOP_BUS = event -> { };

    private static ConceptGateway seededGateway() {
        DefaultConceptGateway gateway = GovernedTestGateways.forConcepts(ConceptSpec.of("Order", "cliente", "total"));
        ExecutionContext ctx = ExecutionContext.of("dev", "operator");
        gateway.save(new ConceptWriteRequest("Order", "o-1", null, Map.of("id", "o-1", "cliente", "acme", "total", 30)), ctx);
        gateway.save(new ConceptWriteRequest("Order", "o-2", null, Map.of("id", "o-2", "cliente", "other", "total", 10)), ctx);
        gateway.save(new ConceptWriteRequest("Order", "o-3", null, Map.of("id", "o-3", "cliente", "acme", "total", 20)), ctx);
        return gateway;
    }

    @Test
    void runQueryAppliesDeclaredWhere() {
        Map<String, CompiledQuery> queries = Map.of(
                "OrdersByCliente", new CompiledQuery(
                        "OrdersByCliente", "Order", "cliente == 'acme'", List.of(), null, List.of(), List.of(), null, null, Map.of(),
                        List.of(), List.of(), null)
        );
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                seededGateway(), NOOP_DISPATCHER, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), queries);

        ProcedureDefinition definition = new ProcedureDefinition(
                "ListAcmeOrders",
                List.of(ProcedureStep.runQuery("run", "OrdersByCliente", "Order", "rows"))
        );
        ProcedureExecutionResult result = executor.execute(definition, Map.of(), ExecutionContext.of("dev", "operator"));

        assertTrue(result.ok());
        @SuppressWarnings("unchecked")
        List<com.npdev.kernel.concepts.ConceptRecord> rows =
                (List<com.npdev.kernel.concepts.ConceptRecord>) result.state().get("rows");
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> "acme".equals(r.data().get("cliente"))));
    }

    @Test
    void runQueryAppliesDeclaredOrderBy() {
        Map<String, CompiledQuery> queries = Map.of(
                "OrdersByCliente", new CompiledQuery(
                        "OrdersByCliente", "Order", "cliente == 'acme'", List.of("total desc"), null,
                        List.of(), List.of(), null, null, Map.of(), List.of(), List.of(), null)
        );
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                seededGateway(), NOOP_DISPATCHER, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), queries);

        ProcedureDefinition definition = new ProcedureDefinition(
                "ListAcmeOrders",
                List.of(ProcedureStep.runQuery("run", "OrdersByCliente", "Order", "rows"))
        );
        ProcedureExecutionResult result = executor.execute(definition, Map.of(), ExecutionContext.of("dev", "operator"));

        @SuppressWarnings("unchecked")
        List<com.npdev.kernel.concepts.ConceptRecord> rows =
                (List<com.npdev.kernel.concepts.ConceptRecord>) result.state().get("rows");
        assertEquals(30, rows.get(0).data().get("total"));
        assertEquals(20, rows.get(1).data().get("total"));
    }

    @Test
    void runQueryAppliesDeclaredLimit() {
        Map<String, CompiledQuery> queries = Map.of(
                "AllOrders", new CompiledQuery(
                        "AllOrders", "Order", null, List.of(), 1, List.of(), List.of(), null, null, Map.of(),
                        List.of(), List.of(), null)
        );
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                seededGateway(), NOOP_DISPATCHER, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), queries);

        ProcedureDefinition definition = new ProcedureDefinition(
                "ListOrders",
                List.of(ProcedureStep.runQuery("run", "AllOrders", "Order", "rows"))
        );
        ProcedureExecutionResult result = executor.execute(definition, Map.of(), ExecutionContext.of("dev", "operator"));

        @SuppressWarnings("unchecked")
        List<com.npdev.kernel.concepts.ConceptRecord> rows =
                (List<com.npdev.kernel.concepts.ConceptRecord>) result.state().get("rows");
        assertEquals(1, rows.size());
    }

    /**
     * X0-7 (REG-100), fixed alongside LC-P0 (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0.3).
     *
     * <p><b>This test used to be called {@code runQueryWithUnknownQueryNameReturnsUnfilteredRows}
     * and asserted {@code rows.size() == 3} — every seeded row.</b> It was pinning the defect, not
     * a feature: naming a query that does not resolve returned the whole table, and the runtime's
     * own comment said so ("Absent from queriesByName -&gt; unfiltered, same as before this fix").
     * That is LC-P0's shape one layer up — a declared QUERY that does not filter, because the name
     * did not resolve — and it is silent, so a rename or a normalization mismatch quietly returns
     * rows the author asked to exclude.
     *
     * <p>Rewritten rather than deleted, so the behaviour change is visible in the diff. Declaring
     * NO query at all is still a plain unfiltered list (see {@code runQueryWithoutAQueryName}) —
     * absent is not the same as unresolvable.
     */
    @Test
    void runQueryWithUnknownQueryNameIsRefusedInsteadOfReturningEveryRow() {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                seededGateway(), NOOP_DISPATCHER, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), Map.of());

        ProcedureDefinition definition = new ProcedureDefinition(
                "ListOrders",
                List.of(ProcedureStep.runQuery("run", "NotDeclared", "Order", "rows"))
        );
        ProcedureExecutionResult result = executor.execute(definition, Map.of(), ExecutionContext.of("dev", "operator"));

        assertFalse(result.ok(), "an unresolvable query name must fail, not return every row");
        assertEquals("QUERY_NOT_FOUND", result.failureCode());
        assertTrue(result.failureMessage().contains("NotDeclared"),
                "the failure must name the query it could not resolve: " + result.failureMessage());
        assertNull(result.state().get("rows"), "no rows may be produced by a refused query");
    }

    /** The control: no query name declared at all is still a plain list, unchanged by LC-P0. */
    @Test
    void runQueryWithoutAQueryNameIsStillAPlainUnfilteredList() {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                seededGateway(), NOOP_DISPATCHER, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), Map.of());

        ProcedureDefinition definition = new ProcedureDefinition(
                "ListOrders",
                List.of(ProcedureStep.runQuery("run", null, "Order", "rows"))
        );
        ProcedureExecutionResult result = executor.execute(definition, Map.of(), ExecutionContext.of("dev", "operator"));

        assertTrue(result.ok(), result.failureMessage());
        @SuppressWarnings("unchecked")
        List<com.npdev.kernel.concepts.ConceptRecord> rows =
                (List<com.npdev.kernel.concepts.ConceptRecord>) result.state().get("rows");
        assertEquals(3, rows.size());
    }
}
