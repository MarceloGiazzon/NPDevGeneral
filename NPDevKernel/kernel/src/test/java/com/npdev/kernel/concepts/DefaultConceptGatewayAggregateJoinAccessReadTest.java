package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.AccessRules;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.FieldDefinition;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 (roadmap B27, ADR-0011 D1) C3: {@link DefaultConceptGateway#aggregate}'s access.read hard stop
 * widened to a {@code groupBy} join's WHOLE path -- the runtime backstop for a hand-built {@link
 * ConceptAggregateRequest} that bypasses {@code PackValidation#validateAggregateQuery} (the
 * compile-time half, covered by {@code AggregateQueryValidationTest}
 * {@code #groupByJoinCrossingIntoAConceptDeclaringAccessReadIsRefused} in NPDevContract/dsl).
 *
 * <p>Fixture: {@code ShipmentEvent(id, warehouse -> Warehouse, unitsShipped)}, no access.read of its
 * own; {@code Warehouse(id, region)}, WHICH declares {@code access.read}. A {@code groupBy}
 * {@code "warehouse.region"} query against {@code ShipmentEvent} must be refused even though
 * {@code ShipmentEvent} itself is unrestricted.
 */
class DefaultConceptGatewayAggregateJoinAccessReadTest {

    private static final String TENANT = "tenant-a";

    @Test
    void groupByJoinCrossingIntoAConceptDeclaringAccessReadIsRefused() {
        DefaultConceptGateway gateway = gatewayWithRestrictedWarehouse();

        ConceptAggregateRequest request = new ConceptAggregateRequest(
                "ShipmentEvent",
                TENANT,
                new ConceptAggregateQuery(
                        List.of(),
                        List.of(new ConceptAggregateQuery.GroupByField("warehouse.region", null)),
                        List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "unitsShipped")),
                        List.of(), List.of(), null));

        ConceptGatewayAccessDeniedException exception = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.aggregate(request, ExecutionContext.of(TENANT, "test-actor")));
        assertEquals("AGGREGATE_ACCESS_READ_UNSUPPORTED", exception.code());
        assertTrue(exception.getMessage().contains("crosses into concept Warehouse"), exception.getMessage());
        assertTrue(exception.getMessage().contains("access.read"), exception.getMessage());
    }

    /** Negative control: an UNRESTRICTED join target must not trip the widened guard -- the request
     *  reaches the store and returns normally. */
    @Test
    void groupByJoinCrossingIntoAnUnrestrictedConceptIsNotRefused() {
        CompiledModel model = joinModel();
        InMemoryConceptStore store = new InMemoryConceptStore(model);
        store.save(new ConceptRecord("Warehouse", "11111111-1111-1111-1111-111111111111", TENANT, Map.of("region", "east")));
        store.save(new ConceptRecord("ShipmentEvent", "22222222-2222-2222-2222-222222222222", TENANT,
                Map.of("warehouse", "11111111-1111-1111-1111-111111111111", "unitsShipped", 10)));

        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(store, model);
        ConceptAggregateRequest request = new ConceptAggregateRequest(
                "ShipmentEvent",
                TENANT,
                new ConceptAggregateQuery(
                        List.of(),
                        List.of(new ConceptAggregateQuery.GroupByField("warehouse.region", null)),
                        List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "unitsShipped")),
                        List.of(), List.of(), null));

        ConceptAggregateResult result = gateway.aggregate(request, ExecutionContext.of(TENANT, "test-actor"));
        assertEquals(1, result.rows().size());
        assertEquals("east", result.rows().get(0).get("warehouse.region"));
        assertEquals(10L, ((Number) result.rows().get(0).get("total")).longValue());
    }

    private static DefaultConceptGateway gatewayWithRestrictedWarehouse() {
        ConceptDefinition warehouse = new ConceptDefinition(
                "Warehouse",
                Map.of(
                        "id", new FieldDefinition("id", true, List.of(), null, null, null),
                        "region", new FieldDefinition("region", true, List.of(), null, null, null)
                ),
                List.of(), null, java.util.Set.of(),
                new AccessRules("region == $user.region", null)
        );
        ConceptDefinition shipmentEvent = ConceptDefinition.of(
                "ShipmentEvent",
                List.of(
                        new FieldDefinition("id", true, List.of(), null, null, null),
                        new FieldDefinition("warehouse", false, List.of(), null, null, null, false, "Warehouse"),
                        new FieldDefinition("unitsShipped", true, List.of(), null, null, null)
                ),
                List.of(), null);

        return new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                new ConfiguredConceptGatewaySemanticPolicy(List.of(warehouse, shipmentEvent)),
                record -> { }
        );
    }

    private static CompiledModel joinModel() {
        CompiledConcept warehouse = new CompiledConcept(
                "Warehouse", "Warehouse", "warehouses",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("region", "string", "String", false, true, false)
                )
        );
        CompiledConcept shipmentEvent = new CompiledConcept(
                "ShipmentEvent", "ShipmentEvent", "shipment_events",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("warehouse", "reference", "String", false, false, false,
                                List.of(), "Warehouse"),
                        new CompiledField("unitsShipped", "int", "Integer", false, true, false)
                )
        );
        return new CompiledModel("s4.groupbyjoin.gateway", "1.0.0", "1.0.0",
                Map.of(warehouse.getName(), warehouse, shipmentEvent.getName(), shipmentEvent));
    }
}
