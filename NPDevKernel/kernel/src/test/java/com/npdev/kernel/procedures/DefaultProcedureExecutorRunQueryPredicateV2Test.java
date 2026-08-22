package com.npdev.kernel.procedures;

import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayTraceRecord;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.concepts.GovernedTestGateways;
import com.npdev.kernel.concepts.GovernedTestGateways.ConceptSpec;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4.3 validation-lockstep gap closure (Roadmap Wave 1): {@code runQuery} now compiles a declared
 * {@code where} through {@code ConceptQueryPredicateCompiler#compileToConceptQueryFilters} (the v2
 * grammar -- OR-groups, {@code in}, {@code contains}/{@code startsWith}, {@code is null}/{@code is
 * not null}, reference-path joins) instead of the v1-only {@code #compile}. This class proves two
 * separate things, deliberately kept apart because they run on different execution paths:
 *
 * <ul>
 *   <li>Every v2 shape that does NOT cross a reference-path join executes correctly through a REAL
 *       (not mocked) {@link com.npdev.kernel.inproc.InMemoryConceptStore}-backed {@link ConceptGateway}
 *       -- {@link com.npdev.kernel.concepts.ConceptQueryEngine} already understands the {@code
 *       OR_GROUPS} marker filter (R4.3), so OR-groups/{@code in}/{@code contains}/{@code startsWith}/
 *       {@code is null} all run correctly in-proc, not just against SQL.</li>
 *   <li>A reference-path predicate (the one v2 shape the in-memory adapter deliberately refuses --
 *       see {@code ConceptQueryEngine#matches}'s own comment, "only JdbcBusinessConceptStore resolves
 *       a reference-path filter field, via a real SQL JOIN") now REACHES that refusal as a named,
 *       graceful step failure instead of being rejected by the v1 grammar before ever compiling --
 *       and, on the production JDBC adapter, resolves via real SQL. {@code
 *       orGroupsWithAReferencePathJoinProducesTheExactFilterShapeJdbcBusinessConceptStoreExecutesOnH2}
 *       proves {@code runQuery} constructs the byte-for-byte SAME {@link ConceptQuery.Filter} shape
 *       ({@code ConceptQuery.Operator#OR_GROUPS} wrapping a reference-path field) that {@code
 *       JdbcBusinessConceptStorePredicateV2Test} (NPDevRuntimeHost, out of this change's owned
 *       surface) hand-builds and proves against a REAL H2 database returns the exact matching row
 *       set -- so {@code runQuery} is proven to reach the already-proven SQL pushdown path, without
 *       this kernel module needing its own JDBC store (which it does not own).</li>
 * </ul>
 */
class DefaultProcedureExecutorRunQueryPredicateV2Test {

    private static final CapabilityDispatcher NOOP_DISPATCHER = (call, state) -> null;
    private static final EventBus NOOP_BUS = event -> { };

    private static DefaultConceptGateway seededOrders() {
        DefaultConceptGateway gateway = GovernedTestGateways.forConcepts(
                ConceptSpec.of("Order", "cliente", "total", "nota", "codigo"));
        ExecutionContext ctx = ExecutionContext.of("dev", "operator");
        gateway.save(new ConceptWriteRequest("Order", "o-1", null,
                Map.of("id", "o-1", "cliente", "acme", "total", 30, "nota", "urgent", "codigo", "ACME-1")), ctx);
        gateway.save(new ConceptWriteRequest("Order", "o-2", null,
                Map.of("id", "o-2", "cliente", "other", "total", 10, "nota", "normal", "codigo", "OTH-2")), ctx);
        gateway.save(new ConceptWriteRequest("Order", "o-3", null,
                Map.of("id", "o-3", "cliente", "third", "total", 50, "nota", "urgent-priority", "codigo", "THI-3")), ctx);
        return gateway;
    }

    private static ProcedureExecutionResult runNamedQuery(
            DefaultConceptGateway gateway, String where, List<Object> ignored) {
        Map<String, CompiledQuery> queries = Map.of(
                "Q", new CompiledQuery("Q", "Order", where, List.of("codigo asc"), null, List.of(), List.of(), null,
                        Map.of(), List.of(), List.of(), null)
        );
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                gateway, NOOP_DISPATCHER, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), queries);
        ProcedureDefinition definition = new ProcedureDefinition(
                "Run", List.of(ProcedureStep.runQuery("run", "Q", "Order", "rows")));
        return executor.execute(definition, Map.of(), ExecutionContext.of("dev", "operator"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> codigos(ProcedureExecutionResult result) {
        List<ConceptRecord> rows = (List<ConceptRecord>) result.state().get("rows");
        return rows.stream().map(r -> String.valueOf(r.data().get("codigo"))).toList();
    }

    @Test
    void orGroupExecutesCorrectlyThroughARealInMemoryStore() {
        ProcedureExecutionResult result = runNamedQuery(
                seededOrders(), "cliente == 'acme' || total > 40", List.of());
        assertTrue(result.ok(), result.failureMessage());
        assertEquals(List.of("ACME-1", "THI-3"), codigos(result));
    }

    @Test
    void inOperatorExecutesCorrectlyThroughARealInMemoryStore() {
        ProcedureExecutionResult result = runNamedQuery(
                seededOrders(), "cliente in ('acme', 'third')", List.of());
        assertTrue(result.ok(), result.failureMessage());
        assertEquals(List.of("ACME-1", "THI-3"), codigos(result));
    }

    @Test
    void containsAndStartsWithExecuteCorrectlyThroughARealInMemoryStore() {
        ProcedureExecutionResult contains = runNamedQuery(
                seededOrders(), "nota contains 'urgent'", List.of());
        assertTrue(contains.ok(), contains.failureMessage());
        assertEquals(List.of("ACME-1", "THI-3"), codigos(contains));

        ProcedureExecutionResult startsWith = runNamedQuery(
                seededOrders(), "nota startsWith 'urgent-'", List.of());
        assertTrue(startsWith.ok(), startsWith.failureMessage());
        assertEquals(List.of("THI-3"), codigos(startsWith));
    }

    @Test
    void isNullAndIsNotNullExecuteCorrectlyThroughARealInMemoryStore() {
        DefaultConceptGateway gateway = seededOrders();
        gateway.save(new ConceptWriteRequest("Order", "o-4", null,
                Map.of("id", "o-4", "cliente", "fourth", "total", 5, "codigo", "FOU-4")), ExecutionContext.of("dev", "operator"));

        ProcedureExecutionResult withNota = runNamedQuery(gateway, "nota is not null", List.of());
        assertTrue(withNota.ok(), withNota.failureMessage());
        assertEquals(List.of("ACME-1", "OTH-2", "THI-3"), codigos(withNota));

        ProcedureExecutionResult withoutNota = runNamedQuery(gateway, "nota is null", List.of());
        assertTrue(withoutNota.ok(), withoutNota.failureMessage());
        assertEquals(List.of("FOU-4"), codigos(withoutNota));
    }

    /**
     * Documents the known, pre-existing boundary this fix does NOT change: {@code
     * ConceptQueryEngine} (the in-memory adapter's default {@code query()}, unmodified by this fix)
     * refuses to resolve a reference-path filter field -- only {@code JdbcBusinessConceptStore} joins
     * one. Before this fix, a reference-path {@code where} could never even compile (the v1 grammar
     * has no dotted paths), so this failure mode did not exist for {@code runQuery} at all; now it
     * compiles and reaches the store, which refuses it as a NAMED step failure -- never silently
     * wrong rows, matching X0 -- exactly the same way it always has for a production app running
     * {@code npdev.storage.mode=jdbc} (where the identical filter instead resolves via a real SQL
     * JOIN, proven by {@code JdbcBusinessConceptStorePredicateV2Test}).
     */
    @Test
    void aReferencePathPredicateFailsAsANamedStepFailureOnTheInMemoryAdapterNotSilentWrongRows() {
        ProcedureExecutionResult result = runNamedQuery(
                seededOrders(), "cliente.country == 'US'", List.of());
        assertFalse(result.ok(), "the in-memory adapter cannot resolve a reference-path field and must "
                + "refuse loudly, not silently return unfiltered/wrong rows");
        assertEquals("PROCEDURE_STEP_FAILED", result.failureCode());
        assertTrue(result.failureMessage().contains("reference-path"),
                "expected the refusal to name the reference-path limitation: " + result.failureMessage());
    }

    /**
     * The H2 half: proves {@code runQuery} constructs the EXACT {@link ConceptQuery} shape (a single
     * {@link ConceptQuery.Operator#OR_GROUPS} marker filter wrapping a reference-path field and a
     * date comparison) that {@code JdbcBusinessConceptStorePredicateV2Test}'s
     * {@code orGroupsWithAReferencePathJoinAndADateComparisonReturnExactlyTheMatchingRows} test
     * (NPDevRuntimeHost, outside this change's owned surface) hand-builds and proves against a real
     * H2 database returns exactly the matching row set. This kernel module has no JDBC store of its
     * own to execute against, so a {@link ConceptGateway} test double captures exactly what {@code
     * runQuery} hands to {@code ConceptGateway#query} -- proving the WIRING is correct, while the SQL
     * execution correctness for this identical shape is the already-passing H2 integration test's job.
     */
    @Test
    void orGroupsWithAReferencePathJoinProducesTheExactFilterShapeJdbcBusinessConceptStoreExecutesOnH2() {
        RecordingConceptGateway recording = new RecordingConceptGateway();
        // where: (customerId.country == 'US' && placedOn < '2026-02-01') || placedOn >= '2026-06-10'
        // -- the SAME predicate JdbcBusinessConceptStorePredicateV2Test proves against real H2.
        String where = "customerId.country == 'US' && placedOn < '2026-02-01' || placedOn >= '2026-06-10'";
        Map<String, CompiledQuery> queries = Map.of(
                "SalesV2", new CompiledQuery("SalesV2", "Order", where, List.of(), null, List.of(), List.of(), null,
                        Map.of(), List.of(), List.of(), null)
        );
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                recording, NOOP_DISPATCHER, NOOP_BUS, Map.of(), ProcedureExecutionLimits.defaults(), queries);
        ProcedureDefinition definition = new ProcedureDefinition(
                "Run", List.of(ProcedureStep.runQuery("run", "SalesV2", "Order", "rows")));
        ProcedureExecutionResult result = executor.execute(definition, Map.of(), ExecutionContext.of("dev", "operator"));

        assertTrue(result.ok(), result.failureMessage());
        assertTrue(recording.lastQuery != null, "runQuery must have called ConceptGateway#query");

        List<ConceptQuery.Filter> filters = recording.lastQuery.filters();
        assertEquals(1, filters.size(), "an OR-of-AND predicate must be a single OR_GROUPS marker filter");
        ConceptQuery.Filter marker = filters.get(0);
        assertEquals(ConceptQuery.Operator.OR_GROUPS, marker.operator());

        @SuppressWarnings("unchecked")
        List<List<ConceptQuery.Filter>> groups = (List<List<ConceptQuery.Filter>>) marker.value();

        // Byte-for-byte the same groups JdbcBusinessConceptStorePredicateV2Test hand-builds and
        // proves against real H2.
        List<List<ConceptQuery.Filter>> expected = List.of(
                List.of(
                        new ConceptQuery.Filter("customerId.country", ConceptQuery.Operator.EQ, "US"),
                        new ConceptQuery.Filter("placedOn", ConceptQuery.Operator.LT, "2026-02-01")
                ),
                List.of(
                        new ConceptQuery.Filter("placedOn", ConceptQuery.Operator.GTE, "2026-06-10")
                )
        );
        assertEquals(expected, groups,
                "runQuery must hand ConceptGateway#query the exact filter shape "
                        + "JdbcBusinessConceptStorePredicateV2Test proves returns the correct rows on real H2");
    }

    /** Captures the {@link ConceptQuery} a {@code runQuery} step hands to {@link #query}, without
     *  needing a real store -- this test class owns no JDBC store (that lives in NPDevRuntimeHost,
     *  outside this change's surface), so the wiring proof stops at "the exact request was built". */
    private static final class RecordingConceptGateway implements ConceptGateway {
        private ConceptQuery lastQuery;

        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
            this.lastQuery = request.query();
            return new ConceptPage(List.of(), 0, false);
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public List<ConceptGatewayTraceRecord> explain() {
            return List.of();
        }
    }
}
