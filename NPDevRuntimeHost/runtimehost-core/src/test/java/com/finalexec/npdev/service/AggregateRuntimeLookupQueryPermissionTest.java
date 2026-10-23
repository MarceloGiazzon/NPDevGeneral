package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.security.PermissionGrant;
import com.npdev.kernel.security.StaticPermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * REG-301: an aggregate lookup field's query is gated by that query's permissionRequirements[]
 * exactly as GET /api/queries/{name} is -- a caller lacking the requirement is refused the load
 * instead of being handed the guarded query's values on every row.
 */
class AggregateRuntimeLookupQueryPermissionTest {

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "t", "version": "1.0",
          "concepts": [
            { "name": "Box", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
            { "name": "Slot", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "boxId", "type": "uuid" },
                { "name": "localId", "type": "string" } ] },
            { "name": "Stock", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "localId", "type": "string" },
                { "name": "onHand", "type": "int" } ] }
          ],
          "queries": [
            { "name": "AuditedStock", "concept": "Stock", "permissionRequirements": [ "stock.audit" ] }
          ],
          "aggregates": [
            { "name": "BoxAggregate", "root": "Box",
              "collections": [ { "name": "slots", "concept": "Slot", "childField": "boxId", "ownership": "owned",
                "lookupFields": [ { "name": "available", "query": "AuditedStock", "joinField": "localId", "valueField": "onHand" } ] } ] }
          ]
        }
        """;

    private ConceptGateway gateway;
    private AggregateRuntime runtime;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static ExecutionContext caller(String role) {
        return ExecutionContext.of("trial", "tester").withRoles(Set.of(role));
    }

    @BeforeEach
    void setUp() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(model), model);
        PermissionEvaluator evaluator = new StaticPermissionEvaluator(List.of(new PermissionGrant("stock.audit", "", "", "auditor")));
        runtime = new AggregateRuntime(new ModelHolder(model), provider(gateway), provider(null), provider(null),
                provider(null), provider(evaluator));
        ExecutionContext seed = caller("auditor");
        gateway.save(new ConceptWriteRequest("Box", "B1", "trial", Map.of("id", "B1")), seed);
        gateway.save(new ConceptWriteRequest("Slot", "S1", "trial", Map.of("id", "S1", "boxId", "B1", "localId", "L1")), seed);
        gateway.save(new ConceptWriteRequest("Stock", "K1", "trial", Map.of("id", "K1", "localId", "L1", "onHand", 7)), seed);
    }

    @Test
    void aCallerHoldingTheRequirementGetsTheLookupValue() {
        Map<String, Object> loaded = runtime.load("BoxAggregate", "B1", caller("auditor"));
        List<?> slots = (List<?>) loaded.get("slots");
        assertEquals(7, ((Map<?, ?>) slots.get(0)).get("available"));
    }

    @Test
    void aCallerLackingTheRequirementIsRefusedTheLoad() {
        ConceptGatewayAccessDeniedException denied = assertThrows(ConceptGatewayAccessDeniedException.class,
                () -> runtime.load("BoxAggregate", "B1", caller("clerk")));
        assertTrue(denied.getMessage().contains("stock.audit"), denied.getMessage());
    }
}
