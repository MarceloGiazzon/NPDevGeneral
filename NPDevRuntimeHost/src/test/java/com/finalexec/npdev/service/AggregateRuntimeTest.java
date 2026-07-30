package com.finalexec.npdev.service;

import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies AggregateRuntime composes the nested tree by joining collections on childField (P0 slice 2). */
class AggregateRuntimeTest {

    private static CompiledModel modelWithExpedicao() {
        CompiledAggregate expedicao = new CompiledAggregate(
                "Expedicao", "Expedicao",
                List.of(new CompiledAggregateCollection(
                        "itens", "ExpedicaoItem", null, "expedicaoId", "owned", null,
                        List.of(
                                new CompiledAggregateCollection("origens", "MovtoOrigem", null, "itemSeq", "owned", null, List.of(), Map.of()),
                                new CompiledAggregateCollection("destinos", "MovtoDestino", null, "itemSeq", "owned", null, List.of(), Map.of())),
                        Map.of())),
                null,
                Map.of(),
                null);
        return new CompiledModel(
                "wms.agg", "1.0.0", "1.0", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(expedicao));
    }

    /** In-memory ConceptGateway: read by id, list by (concept, filterField == filterValue). */
    private static ConceptGateway inMemoryGateway() {
        List<ConceptRecord> data = new ArrayList<>();
        data.add(new ConceptRecord("Expedicao", "E1", "default", Map.of("cliente", "Cliente Alimentos")));
        data.add(new ConceptRecord("ExpedicaoItem", "I1", "default", Map.of("expedicaoId", "E1", "produtoId", "306")));
        data.add(new ConceptRecord("ExpedicaoItem", "I2", "default", Map.of("expedicaoId", "E1", "produtoId", "307")));
        data.add(new ConceptRecord("MovtoOrigem", "O1", "default", Map.of("itemSeq", "I1", "local", "F031")));
        data.add(new ConceptRecord("MovtoOrigem", "O2", "default", Map.of("itemSeq", "I1", "local", "PP")));
        data.add(new ConceptRecord("MovtoOrigem", "O3", "default", Map.of("itemSeq", "I2", "local", "G027")));
        data.add(new ConceptRecord("MovtoDestino", "D1", "default", Map.of("itemSeq", "I1", "local", "EXP01")));

        return new ConceptGateway() {
            @Override
            public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
                return data.stream()
                        .filter(r -> r.conceptName().equals(request.conceptName()) && r.id().equals(request.id()))
                        .findFirst();
            }

            @Override
            public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
                return data.stream()
                        .filter(r -> r.conceptName().equals(request.conceptName()))
                        .filter(r -> request.filterField() == null
                                || String.valueOf(r.data().get(request.filterField())).equals(request.filterValue()))
                        .toList();
            }

            @Override
            public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(ConceptReadRequest request, ExecutionContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsNestedTreeJoinedOnChildField() {
        AggregateRuntime runtime = new AggregateRuntime(modelWithExpedicao(), inMemoryGateway());

        Map<String, Object> tree = runtime.load("Expedicao", "E1", ExecutionContext.anonymous());

        assertEquals("Expedicao", tree.get("aggregate"));
        assertEquals("E1", tree.get("id"));
        assertEquals("Cliente Alimentos", tree.get("cliente"));

        List<Map<String, Object>> itens = (List<Map<String, Object>>) tree.get("itens");
        assertEquals(2, itens.size());

        Map<String, Object> item1 = itens.get(0);
        assertEquals("I1", item1.get("id"));
        assertEquals("306", item1.get("produtoId"));
        List<Map<String, Object>> origens1 = (List<Map<String, Object>>) item1.get("origens");
        List<Map<String, Object>> destinos1 = (List<Map<String, Object>>) item1.get("destinos");
        assertEquals(2, origens1.size(), "I1 should have two origens (O1, O2)");
        assertEquals(1, destinos1.size(), "I1 should have one destino (D1)");
        assertEquals("EXP01", destinos1.get(0).get("local"));

        Map<String, Object> item2 = itens.get(1);
        assertEquals("I2", item2.get("id"));
        assertEquals(1, ((List<?>) item2.get("origens")).size());
        assertEquals(0, ((List<?>) item2.get("destinos")).size(), "I2 has no destinos");
    }

    @Test
    void unknownAggregateThrows() {
        AggregateRuntime runtime = new AggregateRuntime(modelWithExpedicao(), inMemoryGateway());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.load("Nope", "E1", ExecutionContext.anonymous()));
    }

    @Test
    void missingRootThrows() {
        AggregateRuntime runtime = new AggregateRuntime(modelWithExpedicao(), inMemoryGateway());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.load("Expedicao", "NOPE", ExecutionContext.anonymous()));
    }
}
