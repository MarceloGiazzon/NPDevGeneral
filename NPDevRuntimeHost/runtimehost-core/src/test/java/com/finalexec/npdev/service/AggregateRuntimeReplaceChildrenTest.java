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

/**
 * P8: a commit that replaces children with NEW id-less rows at the same unique key (a mosaic cell's
 * (mosaicId,row,col)) must succeed -- the reconcile delete runs before the upserts, not after.
 */
class AggregateRuntimeReplaceChildrenTest {

    private static CompiledModel model() {
        CompiledAggregate mosaic = new CompiledAggregate("MosaicAggregate", "Mosaic",
                List.of(new CompiledAggregateCollection("cells", "MosaicCell", null, "mosaicId", "owned", null,
                        List.of(), Map.of())),
                null, Map.of(), null);
        return new CompiledModel("demo.mosaic", "1.0.0", "1.0", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(mosaic));
    }

    /** In-memory store with a unique index on MosaicCell (mosaicId, row), like ux_mosaic_cell_pos. */
    private static ConceptGateway uniqueCellGateway(List<ConceptRecord> data) {
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
                data.removeIf(r -> r.conceptName().equals(request.conceptName()) && r.id().equals(request.id()));
                if (request.conceptName().equals("MosaicCell")) {
                    for (ConceptRecord r : data) {
                        if (r.conceptName().equals("MosaicCell")
                                && String.valueOf(r.data().get("mosaicId")).equals(String.valueOf(request.data().get("mosaicId")))
                                && String.valueOf(r.data().get("row")).equals(String.valueOf(request.data().get("row")))) {
                            throw new IllegalStateException("unique index ux_mosaic_cell_pos violated");
                        }
                    }
                }
                ConceptRecord saved = new ConceptRecord(request.conceptName(), request.id(), "default", request.data());
                data.add(saved);
                return saved;
            }

            @Override
            public void delete(ConceptReadRequest request, ExecutionContext context) {
                data.removeIf(r -> r.conceptName().equals(request.conceptName()) && r.id().equals(request.id()));
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacingChildrenWithIdLessRowsAtTheSameUniqueKeySucceeds() {
        List<ConceptRecord> data = new ArrayList<>();
        data.add(new ConceptRecord("Mosaic", "M1", "default", Map.of("title", "Old")));
        data.add(new ConceptRecord("MosaicCell", "C1", "default", Map.of("mosaicId", "M1", "row", 0, "capId", "old")));
        data.add(new ConceptRecord("MosaicCell", "C2", "default", Map.of("mosaicId", "M1", "row", 1, "capId", "old")));
        AggregateRuntime runtime = new AggregateRuntime(model(), uniqueCellGateway(data));

        Map<String, Object> saved = runtime.commit("MosaicAggregate", Map.of("id", "M1", "title", "Flag",
                "cells", List.of(Map.of("row", 0, "capId", "blue"), Map.of("row", 1, "capId", "white"))),
                ExecutionContext.anonymous());

        List<Map<String, Object>> cells = (List<Map<String, Object>>) saved.get("cells");
        assertEquals(2, cells.size());
        assertEquals(List.of("blue", "white"), cells.stream().map(c -> c.get("capId")).toList());
        assertTrue(cells.stream().noneMatch(c -> c.get("id").equals("C1") || c.get("id").equals("C2")));
        assertEquals("Flag", saved.get("title"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keptChildIdsSurviveAndOmittedOnesAreDeleted() {
        List<ConceptRecord> data = new ArrayList<>();
        data.add(new ConceptRecord("Mosaic", "M1", "default", Map.of("title", "Old")));
        data.add(new ConceptRecord("MosaicCell", "C1", "default", Map.of("mosaicId", "M1", "row", 0, "capId", "a")));
        data.add(new ConceptRecord("MosaicCell", "C2", "default", Map.of("mosaicId", "M1", "row", 1, "capId", "b")));
        AggregateRuntime runtime = new AggregateRuntime(model(), uniqueCellGateway(data));

        Map<String, Object> saved = runtime.commit("MosaicAggregate", Map.of("id", "M1", "title", "Old",
                "cells", List.of(Map.of("id", "C1", "row", 0, "capId", "z"))), ExecutionContext.anonymous());

        List<Map<String, Object>> cells = (List<Map<String, Object>>) saved.get("cells");
        assertEquals(1, cells.size());
        assertEquals("C1", cells.get(0).get("id"));
        assertEquals("z", cells.get(0).get("capId"));
    }
}
