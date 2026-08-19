package com.npdev.kernel.procedures;

import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptRecord;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * LIFT-QUERY-P2: proves a {@code runQuery(where)} step's filtered {@code List<ConceptRecord>}
 * output is directly consumable as a {@code callCapability} argument -- the second half of
 * ARCH-7 ("results were not importable by a sandboxed capability"). No new dispatcher code was
 * needed: {@code RegistryCapabilityDispatcher}/reflective invocation matches by name+arity only
 * (type-erased at the JVM level), so a {@code List<ConceptRecord>} already passes through
 * mechanically -- this test is the missing end-to-end proof that chain actually works, plus the
 * explicit sandbox check the roadmap calls for.
 */
class DefaultProcedureExecutorQueryToCapabilityTest {

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
    void filteredQueryRowsFlowIntoCapabilityCallUnmodified() {
        Map<String, CompiledQuery> queries = Map.of(
                "OrdersByCliente", new CompiledQuery(
                        "OrdersByCliente", "Order", "cliente == 'acme'", List.of(), null, List.of(), List.of(), null, Map.of(),
                        List.of(), List.of(), null)
        );

        // A capability that is structurally incapable of DB access -- it closes over nothing but
        // the arg it receives, proving the "capability holds no data handle" sandbox requirement
        // isn't just a convention but a fact about this specific call.
        CapabilityDispatcher pureSummingCapability = (call, state) -> {
            assertEquals(1, call.args().size());
            Object arg = call.args().get(0);
            assertInstanceOf(List.class, arg, "expected the runQuery output list to pass through unmodified");
            @SuppressWarnings("unchecked")
            List<ConceptRecord> rows = (List<ConceptRecord>) arg;
            double sum = rows.stream().mapToDouble(r -> ((Number) r.data().get("total")).doubleValue()).sum();
            return CapabilityResult.success(Map.of("sum", sum, "count", rows.size()));
        };

        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                seededGateway(), pureSummingCapability, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), queries);

        ProcedureDefinition definition = new ProcedureDefinition(
                "SumAcmeOrders",
                List.of(
                        ProcedureStep.runQuery("query-acme", "OrdersByCliente", "Order", "acmeRows"),
                        ProcedureStep.callCapability(
                                "sum-totals", "totals", "SummaryCapability", "inproc", "sum",
                                List.of("acmeRows"), "summary"
                        )
                )
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), ExecutionContext.of("dev", "operator"));

        assertTrue(result.ok(), () -> "procedure failed: " + result.failureCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.state().get("summary");
        // Only the two "acme" rows (total 30 + 20) should have reached the capability -- the
        // "other"-cliente row was filtered out by runQuery's where before the capability ever ran.
        assertEquals(50.0, summary.get("sum"));
        assertEquals(2, summary.get("count"));
    }
}
