package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies aggregates survive the compiled-model canonical JSON round trip (P0 slice 2). */
class CompiledAggregateCanonicalJsonTest {

    private static CompiledModel modelWithExpedicaoAggregate() {
        CompiledAggregate expedicao = new CompiledAggregate(
                "Expedicao", "Expedicao",
                List.of(new CompiledAggregateCollection(
                        "itens", "ExpedicaoItem", "bondExpedicaoItem", "expedicaoId", "owned", "produtoId",
                        List.of(
                                new CompiledAggregateCollection(
                                        "origens", "MovtoOrigem", null, "itemSeq", "owned", null,
                                        List.of(), Map.of()),
                                new CompiledAggregateCollection(
                                        "destinos", "MovtoDestino", null, "itemSeq", "owned", null,
                                        List.of(), Map.of())),
                        Map.of())),
                null,
                Map.of(),
                "ValidateExpedicaoProcedure");

        return new CompiledModel(
                "wms.agg", "1.0.0", "1.0",
                Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(expedicao));
    }

    @Test
    void aggregatesSurviveCanonicalRoundTrip() throws Exception {
        String json = CompiledModelCanonicalJson.toJson(modelWithExpedicaoAggregate());
        CompiledModel back = CompiledModelCanonicalJsonReader.fromJson(json);

        assertEquals(1, back.getAggregates().size());
        CompiledAggregate agg = back.getAggregates().get(0);
        assertEquals("Expedicao", agg.name());
        assertEquals("Expedicao", agg.root());
        // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3B): onValidate must survive the round
        // trip alongside onCommit -- a sibling field, same writer/reader risk LNCH-1 already flagged.
        assertEquals("ValidateExpedicaoProcedure", agg.onValidate());

        assertEquals(1, agg.collections().size());
        CompiledAggregateCollection itens = agg.collections().get(0);
        assertEquals("itens", itens.name());
        assertEquals("ExpedicaoItem", itens.concept());
        assertEquals("expedicaoId", itens.childField());
        assertEquals("owned", itens.ownership());
        assertEquals("produtoId", itens.orderBy());

        // The defining feature: a second level of owned collections survives intact.
        assertEquals(2, itens.collections().size());
        assertEquals(List.of("origens", "destinos"),
                itens.collections().stream().map(CompiledAggregateCollection::name).toList());
        assertEquals("MovtoOrigem", itens.collections().get(0).concept());
        assertEquals("MovtoDestino", itens.collections().get(1).concept());
        assertEquals("itemSeq", itens.collections().get(1).childField());
    }
}
