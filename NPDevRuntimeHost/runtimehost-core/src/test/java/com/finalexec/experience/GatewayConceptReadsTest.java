package com.finalexec.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptAggregateRequest;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * FORM P5-T4: {@link GatewayConceptReads} reaches the store ONLY through the caller's own gateway context (the same
 * {@code query}/{@code aggregate} door the REST API uses) and resolves {@code $user} against that caller.
 */
class GatewayConceptReadsTest {

    private static final ExecutionContext CALLER =
            ExecutionContext.of("tenant-1", "alice").withRoles(Set.of("WAREHOUSEMANAGER"));

    /** Records what it was asked and with which context; every other door is closed. */
    private static class RecordingGateway implements ConceptGateway {
        ConceptQueryRequest query;
        ConceptAggregateRequest aggregate;
        ExecutionContext seenContext;
        ConceptAggregateResult aggregateAnswer = new ConceptAggregateResult(List.of(Map.of("value", new BigDecimal("9.5"))));

        @Override
        public ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
            this.query = request;
            this.seenContext = context;
            return new ConceptPage(List.of(), 42, true);
        }

        @Override
        public ConceptAggregateResult aggregate(ConceptAggregateRequest request, ExecutionContext context) {
            this.aggregate = request;
            this.seenContext = context;
            return aggregateAnswer;
        }

        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            throw new AssertionError("read is not part of an eval");
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            throw new AssertionError("list is not part of an eval");
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            throw new AssertionError("an eval never writes");
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            throw new AssertionError("an eval never deletes");
        }
    }

    @Test
    void countIsTheGatewaysTotalForOneRowAsTheCallerInTheirTenantWithTheWhereAsFiltersAndUserBound() {
        RecordingGateway gateway = new RecordingGateway();
        long total = new GatewayConceptReads(gateway, CALLER).count("WidgetOrder", "ownerId == $user.id && status == 'NEW'");

        assertEquals(42, total, "the total comes from the gateway, which already scopes it to what the caller may read");
        assertEquals(CALLER, gateway.seenContext, "evaluated as the caller, never as the system");
        assertEquals("WidgetOrder", gateway.query.conceptName());
        assertEquals("tenant-1", gateway.query.tenantId());
        assertEquals(1, gateway.query.query().limit());
        List<ConceptQuery.Filter> filters = gateway.query.query().filters();
        assertEquals(2, filters.size());
        assertEquals("ownerId", filters.get(0).field());
        assertEquals("alice", filters.get(0).value(), "$user.id means THIS caller");
        assertEquals("status", filters.get(1).field());
        assertEquals("NEW", filters.get(1).value());
    }

    @Test
    void countWithoutAPredicateHasNoFilters() {
        RecordingGateway gateway = new RecordingGateway();
        new GatewayConceptReads(gateway, CALLER).count("WidgetOrder", null);
        assertEquals(List.of(), gateway.query.query().filters());
    }

    @Test
    void aPredicateOutsideThePushableGrammarThrowsSoTheEvaluatorAnswersNull() {
        RecordingGateway gateway = new RecordingGateway();
        GatewayConceptReads reads = new GatewayConceptReads(gateway, CALLER);
        assertThrows(RuntimeException.class, () -> reads.count("WidgetOrder", "isToday(createdAt)"));
        assertEquals(null, gateway.query, "nothing was asked of the store");
    }

    @Test
    void sumAndAvgAreAnUngroupedGatewayAggregateAsTheCaller() {
        RecordingGateway gateway = new RecordingGateway();
        BigDecimal sum = new GatewayConceptReads(gateway, CALLER).aggregate("WidgetOrder", "sum", "qty", "status == 'PAID'");

        assertEquals(new BigDecimal("9.5"), sum);
        assertEquals(CALLER, gateway.seenContext);
        assertEquals("tenant-1", gateway.aggregate.tenantId());
        assertEquals(List.of(), gateway.aggregate.query().groupBy());
        assertEquals("sum", gateway.aggregate.query().aggregates().get(0).fn());
        assertEquals("qty", gateway.aggregate.query().aggregates().get(0).field());
        assertEquals("PAID", gateway.aggregate.query().filters().get(0).value());
    }

    @Test
    void anAggregateWithNoRowOrNoValueIsNull() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.aggregateAnswer = new ConceptAggregateResult(List.of());
        assertNull(new GatewayConceptReads(gateway, CALLER).aggregate("WidgetOrder", "avg", "qty", null));
        gateway.aggregateAnswer = new ConceptAggregateResult(List.of(new java.util.HashMap<String, Object>(Map.of())));
        assertNull(new GatewayConceptReads(gateway, CALLER).aggregate("WidgetOrder", "avg", "qty", null));
    }

    @Test
    void theEvaluatorTurnsAGatewayDenialIntoNull() {
        ConceptGateway denying = new RecordingGateway() {
            @Override
            public ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
                throw new com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException("DENIED", "no concept.list");
            }
        };
        String badge = "count('WidgetOrder')";
        ExperienceEvaluator.Caller caller = new ExperienceEvaluator.Caller("alice", "tenant-1", Set.of("WAREHOUSEMANAGER"), false);
        Map<String, Object> values = new ExperienceEvaluator().evaluate(
                Map.of("k", badge), Set.of(badge), caller, new GatewayConceptReads(denying, CALLER));
        assertNull(values.get("k"));
    }
}
