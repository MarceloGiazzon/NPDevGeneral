package com.npdev.kernel.procedures;

import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LIFT-QUERY-P1: a `runQuery` procedure step honors the named query's where/orderBy/limit. */
class DefaultProcedureExecutorRunQueryTest {

    private static final CapabilityDispatcher NOOP_DISPATCHER = (call, state) -> null;
    private static final EventBus NOOP_BUS = event -> { };

    private static ConceptGateway seededGateway() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        ExecutionContext ctx = ExecutionContext.of("dev", "operator");
        gateway.save(new ConceptWriteRequest("Order", "o-1", null, Map.of("cliente", "acme", "total", 30)), ctx);
        gateway.save(new ConceptWriteRequest("Order", "o-2", null, Map.of("cliente", "other", "total", 10)), ctx);
        gateway.save(new ConceptWriteRequest("Order", "o-3", null, Map.of("cliente", "acme", "total", 20)), ctx);
        return gateway;
    }

    @Test
    void runQueryAppliesDeclaredWhere() {
        Map<String, CompiledQuery> queries = Map.of(
                "OrdersByCliente", new CompiledQuery(
                        "OrdersByCliente", "Order", "cliente == 'acme'", List.of(), null, List.of(), List.of(), null, null, Map.of())
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
                        List.of(), List.of(), null, null, Map.of())
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
                        "AllOrders", "Order", null, List.of(), 1, List.of(), List.of(), null, null, Map.of())
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

    @Test
    void runQueryWithUnknownQueryNameReturnsUnfilteredRows() {
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                seededGateway(), NOOP_DISPATCHER, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), Map.of());

        ProcedureDefinition definition = new ProcedureDefinition(
                "ListOrders",
                List.of(ProcedureStep.runQuery("run", "NotDeclared", "Order", "rows"))
        );
        ProcedureExecutionResult result = executor.execute(definition, Map.of(), ExecutionContext.of("dev", "operator"));

        assertTrue(result.ok());
        @SuppressWarnings("unchecked")
        List<com.npdev.kernel.concepts.ConceptRecord> rows =
                (List<com.npdev.kernel.concepts.ConceptRecord>) result.state().get("rows");
        assertEquals(3, rows.size());
    }
}
