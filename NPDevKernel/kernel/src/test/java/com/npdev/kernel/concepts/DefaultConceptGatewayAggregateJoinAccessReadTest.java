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

    /**
     * S8 W1.1 (roadmap deferred item #1): the SAME widened guard, now proven across a SECOND hop --
     * {@code Warehouse} (the near hop) declares no access.read of its own, but {@code Country} (the
     * far hop, reached via {@code warehouse.country.name}) does. The loop in {@code
     * DefaultConceptGateway#aggregate} must keep walking past an unrestricted hop rather than
     * stopping at the first one.
     */
    @Test
    void twoHopGroupByJoinCrossingIntoAConceptDeclaringAccessReadAtTheFarHopIsRefused() {
        DefaultConceptGateway gateway = gatewayWithRestrictedCountryTwoHops();

        ConceptAggregateRequest request = new ConceptAggregateRequest(
                "ShipmentEvent",
                TENANT,
                new ConceptAggregateQuery(
                        List.of(),
                        List.of(new ConceptAggregateQuery.GroupByField("warehouse.country.name", null)),
                        List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "unitsShipped")),
                        List.of(), List.of(), null));

        ConceptGatewayAccessDeniedException exception = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.aggregate(request, ExecutionContext.of(TENANT, "test-actor")));
        assertEquals("AGGREGATE_ACCESS_READ_UNSUPPORTED", exception.code());
        assertTrue(exception.getMessage().contains("crosses into concept Country"), exception.getMessage());
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

    /**
     * 2026-10-07 (Pigmentampas): a translatable access.read on the JOINED concept is pushed down into
     * the aggregate's WHERE for this caller -- the totals cover only warehouses the caller may read,
     * and a role constant ($user.roles.contains) widens it per caller.
     */
    @Test
    void translatableAccessReadOnAJoinedConceptScopesTheTotalsPerCaller() {
        // The in-memory store refuses reference-path filters by design (only the JDBC store joins),
        // so this pins the exact predicate the gateway hands the store.
        List<ConceptAggregateQuery> seen = new java.util.ArrayList<>();
        DefaultConceptGateway gateway = seededGateway(
                new AccessRules("region == 'east' || $user.roles.contains('Auditor')", null), null, seen);
        ConceptAggregateRequest request = totalByRegion();

        gateway.aggregate(request, ExecutionContext.of(TENANT, "clerk"));
        assertEquals(List.of(ConceptQuery.Filter.eq("warehouse.region", "east")), seen.get(0).filters());

        ExecutionContext auditor = new ExecutionContext(TENANT, "audit-1", Map.of(), java.util.Set.of("auditor"));
        assertEquals(2, gateway.aggregate(request, auditor).rows().size());
        assertTrue(seen.get(1).filters().isEmpty(), seen.get(1).filters().toString());
    }

    /** The base concept's own translatable access.read ($user.id) scopes the totals to the caller's rows. */
    @Test
    void translatableAccessReadOnTheBaseConceptScopesTheTotalsToTheCaller() {
        DefaultConceptGateway gateway = seededGateway(null, new AccessRules("createdBy == $user.id", null),
                new java.util.ArrayList<>());
        ConceptAggregateRequest request = new ConceptAggregateRequest("ShipmentEvent", TENANT,
                new ConceptAggregateQuery(List.of(), List.of(),
                        List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "unitsShipped")),
                        List.of(), List.of(), null));

        ConceptAggregateResult mine = gateway.aggregate(request, ExecutionContext.of(TENANT, "ana"));
        assertEquals(10L, ((Number) mine.rows().get(0).get("total")).longValue());
        ConceptAggregateResult nobody = gateway.aggregate(request, ExecutionContext.of(TENANT, "zed"));
        assertTrue(nobody.rows().isEmpty()
                || ((Number) nobody.rows().get(0).getOrDefault("total", 0)).longValue() == 0L, nobody.rows().toString());
    }

    private static ConceptAggregateRequest totalByRegion() {
        return new ConceptAggregateRequest("ShipmentEvent", TENANT,
                new ConceptAggregateQuery(
                        List.of(),
                        List.of(new ConceptAggregateQuery.GroupByField("warehouse.region", null)),
                        List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "unitsShipped")),
                        List.of(), List.of(), null));
    }

    private static DefaultConceptGateway seededGateway(
            AccessRules warehouseRead, AccessRules shipmentRead, List<ConceptAggregateQuery> seen) {
        CompiledModel model = joinModel();
        InMemoryConceptStore store = new InMemoryConceptStore(model);
        store.save(new ConceptRecord("Warehouse", "11111111-1111-1111-1111-111111111111", TENANT, Map.of("region", "east")));
        store.save(new ConceptRecord("Warehouse", "33333333-3333-3333-3333-333333333333", TENANT, Map.of("region", "west")));
        store.save(new ConceptRecord("ShipmentEvent", "22222222-2222-2222-2222-222222222222", TENANT,
                Map.of("warehouse", "11111111-1111-1111-1111-111111111111", "unitsShipped", 10, "createdBy", "ana")));
        store.save(new ConceptRecord("ShipmentEvent", "44444444-4444-4444-4444-444444444444", TENANT,
                Map.of("warehouse", "33333333-3333-3333-3333-333333333333", "unitsShipped", 7, "createdBy", "bob")));
        ConceptDefinition warehouse = new ConceptDefinition(
                "Warehouse",
                Map.of(
                        "id", new FieldDefinition("id", true, List.of(), null, null, null),
                        "region", new FieldDefinition("region", true, List.of(), null, null, null)
                ),
                List.of(), null, java.util.Set.of(), warehouseRead);
        ConceptDefinition shipmentEvent = new ConceptDefinition(
                "ShipmentEvent",
                Map.of(
                        "id", new FieldDefinition("id", true, List.of(), null, null, null),
                        "warehouse", new FieldDefinition("warehouse", false, List.of(), null, null, null, false, "Warehouse"),
                        "unitsShipped", new FieldDefinition("unitsShipped", true, List.of(), null, null, null),
                        "createdBy", new FieldDefinition("createdBy", false, List.of(), null, null, null)
                ),
                List.of(), null, java.util.Set.of(), shipmentRead);
        com.npdev.kernel.ports.ConceptStore recording = (com.npdev.kernel.ports.ConceptStore) java.lang.reflect.Proxy.newProxyInstance(
                com.npdev.kernel.ports.ConceptStore.class.getClassLoader(),
                new Class<?>[]{com.npdev.kernel.ports.ConceptStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("aggregate")) {
                        ConceptAggregateQuery query = (ConceptAggregateQuery) args[2];
                        seen.add(query);
                        boolean joinFilter = query.filters().stream().anyMatch(f -> f.field().contains("."));
                        if (joinFilter) {
                            return new ConceptAggregateResult(List.of());
                        }
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        return new DefaultConceptGateway(
                recording,
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                new ConfiguredConceptGatewaySemanticPolicy(List.of(warehouse, shipmentEvent)),
                record -> { }
        );
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

    /** S8 W1.1: Warehouse (near hop) is unrestricted; Country (far hop) declares access.read. */
    private static DefaultConceptGateway gatewayWithRestrictedCountryTwoHops() {
        ConceptDefinition country = new ConceptDefinition(
                "Country",
                Map.of(
                        "id", new FieldDefinition("id", true, List.of(), null, null, null),
                        "name", new FieldDefinition("name", true, List.of(), null, null, null)
                ),
                List.of(), null, java.util.Set.of(),
                new AccessRules("name == $user.region", null)
        );
        ConceptDefinition warehouse = ConceptDefinition.of(
                "Warehouse",
                List.of(
                        new FieldDefinition("id", true, List.of(), null, null, null),
                        new FieldDefinition("region", true, List.of(), null, null, null),
                        new FieldDefinition("country", false, List.of(), null, null, null, false, "Country")
                ),
                List.of(), null);
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
                new ConfiguredConceptGatewaySemanticPolicy(List.of(country, warehouse, shipmentEvent)),
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
                        new CompiledField("unitsShipped", "int", "Integer", false, true, false),
                        new CompiledField("createdBy", "string", "String", false, false, false)
                )
        );
        return new CompiledModel("s4.groupbyjoin.gateway", "1.0.0", "1.0.0",
                Map.of(warehouse.getName(), warehouse, shipmentEvent.getName(), shipmentEvent));
    }
}
