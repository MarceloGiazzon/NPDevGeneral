package com.finalexec.npdev.service;

import com.finalexec.acceptance.InProcessAcceptanceRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
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

/** Verifies AggregateRuntime.commit persists a draft tree and reconciles inserts/updates/deletes (P4 write path). */
class AggregateRuntimeCommitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "3.0.0",
          "namespace": "wms",
          "version": "1.0",
          "concepts": [
            {
              "name": "Expedicao",
              "fields": [
                {
                  "name": "id",
                  "type": "string",
                  "id": true,
                  "required": true
                },
                {
                  "name": "cliente",
                  "type": "string"
                }
              ],
              "composition": {
                "collections": [
                  {
                    "name": "itens",
                    "concept": "ExpedicaoItem",
                    "childField": "expedicaoId",
                    "ownership": "owned",
                    "collections": [
                      {
                        "name": "origens",
                        "concept": "MovtoOrigem",
                        "childField": "itemSeq",
                        "ownership": "owned"
                      }
                    ]
                  }
                ]
              }
            },
            {
              "name": "ExpedicaoItem",
              "fields": [
                {
                  "name": "id",
                  "type": "string",
                  "id": true,
                  "required": true
                },
                {
                  "name": "expedicaoId",
                  "type": "string"
                },
                {
                  "name": "produtoId",
                  "type": "string"
                }
              ]
            },
            {
              "name": "MovtoOrigem",
              "fields": [
                {
                  "name": "id",
                  "type": "string",
                  "id": true,
                  "required": true
                },
                {
                  "name": "itemSeq",
                  "type": "string"
                },
                {
                  "name": "local",
                  "type": "string"
                }
              ]
            }
          ],
          "flows": [
            {
              "name": "GerarDemanda",
              "expose": "internal",
              "steps": [
                {
                  "type": "map",
                  "select": { "clienteEcho": "$input.cliente" },
                  "output": "patch"
                },
                {
                  "type": "return",
                  "value": "$patch"
                }
              ]
            },
            {
              "name": "ProporDraft",
              "expose": "internal",
              "steps": [
                {
                  "type": "set",
                  "value": "$input.cliente",
                  "output": "scratch"
                },
                {
                  "type": "return",
                  "value": "$input"
                }
              ]
            },
            {
              "name": "LerCliente",
              "expose": "internal",
              "steps": [
                {
                  "type": "set",
                  "value": "$input.cliente",
                  "output": "scratch"
                },
                {
                  "type": "return",
                  "value": "$input.cliente"
                }
              ]
            }
          ]
        }
        """;

    private static CompiledModel compiledModel() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        return new ModelCompiler().compile(ast);
    }

    /** A mutable in-memory gateway supporting upsert + delete. */
    private static final class Store implements ConceptGateway {
        final List<ConceptRecord> data = new ArrayList<>();

        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest r, ExecutionContext c) {
            return data.stream().filter(x -> x.conceptName().equals(r.conceptName()) && x.id().equals(r.id())).findFirst();
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest r, ExecutionContext c) {
            return data.stream()
                    .filter(x -> x.conceptName().equals(r.conceptName()))
                    .filter(x -> r.filterField() == null
                            || String.valueOf(x.data().get(r.filterField())).equals(r.filterValue()))
                    .toList();
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest r, ExecutionContext c) {
            data.removeIf(x -> x.conceptName().equals(r.conceptName()) && x.id().equals(r.id()));
            ConceptRecord rec = new ConceptRecord(r.conceptName(), r.id(), r.tenantId(), r.data());
            data.add(rec);
            return rec;
        }

        @Override
        public void delete(ConceptReadRequest r, ExecutionContext c) {
            data.removeIf(x -> x.conceptName().equals(r.conceptName()) && x.id().equals(r.id()));
        }

        long count(String concept) {
            return data.stream().filter(x -> x.conceptName().equals(concept)).count();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitInsertsThenReconcilesUpdatesInsertsAndDeletes() throws Exception {
        Store store = new Store();
        AggregateRuntime runtime = new AggregateRuntime(compiledModel(), store);
        ExecutionContext ctx = ExecutionContext.anonymous();

        // 1. Commit a fresh tree: E1 -> item I1 -> origem O1.
        Map<String, Object> draft = new java.util.LinkedHashMap<>(Map.of(
                "id", "E1", "cliente", "Alimentos",
                "itens", List.of(new java.util.LinkedHashMap<>(Map.of(
                        "id", "I1", "produtoId", "306",
                        "origens", List.of(new java.util.LinkedHashMap<>(Map.of("id", "O1", "local", "F031"))))))));
        Map<String, Object> back = runtime.commit("Expedicao", draft, ctx);

        assertEquals("E1", back.get("id"));
        assertEquals("Alimentos", back.get("cliente"));
        assertEquals(1, store.count("Expedicao"));
        assertEquals(1, store.count("ExpedicaoItem"));
        assertEquals(1, store.count("MovtoOrigem"));
        // childField wired: the item points back to E1, the origem back to I1.
        assertEquals("E1", store.read(new ConceptReadRequest("ExpedicaoItem", "I1", null), ctx).get().data().get("expedicaoId"));
        assertEquals("I1", store.read(new ConceptReadRequest("MovtoOrigem", "O1", null), ctx).get().data().get("itemSeq"));
        // The id field must be present in the write payload (DefaultConceptGateway enforces it).
        assertEquals("E1", store.read(new ConceptReadRequest("Expedicao", "E1", null), ctx).get().data().get("id"));
        assertEquals("I1", store.read(new ConceptReadRequest("ExpedicaoItem", "I1", null), ctx).get().data().get("id"));

        // 2. Commit a modified tree: change I1's field, drop O1, add a new item I2 (no id -> generated).
        Map<String, Object> draft2 = new java.util.LinkedHashMap<>(Map.of(
                "id", "E1", "cliente", "Alimentos S/A",
                "itens", List.of(
                        new java.util.LinkedHashMap<>(Map.of("id", "I1", "produtoId", "307", "origens", List.of())),
                        new java.util.LinkedHashMap<>(Map.of("produtoId", "999", "origens", List.of())))));
        Map<String, Object> back2 = runtime.commit("Expedicao", draft2, ctx);

        assertEquals("Alimentos S/A", back2.get("cliente"), "root updated");
        assertEquals(0, store.count("MovtoOrigem"), "removed origem O1 reconciled away");
        assertEquals(2, store.count("ExpedicaoItem"), "I1 updated + I2 inserted");
        assertEquals("307", store.read(new ConceptReadRequest("ExpedicaoItem", "I1", null), ctx).get().data().get("produtoId"));
        List<Map<String, Object>> itens = (List<Map<String, Object>>) back2.get("itens");
        assertEquals(2, itens.size());
    }

    /** invoke() runs a declared procedure over the draft and returns the patched draft without persisting (P6 slice 2). */
    @Test
    void invokeRunsProcedureOverDraftAndReturnsPatchedDraftWithoutPersisting() throws Exception {
        Store store = new Store();
        CompiledModel model = compiledModel();
        AggregateRuntime runtime = new AggregateRuntime(model, store, wired(model, store));
        ExecutionContext ctx = ExecutionContext.anonymous();

        Map<String, Object> draft = new java.util.LinkedHashMap<>(Map.of(
                "id", "E9", "cliente", "Alimentos",
                "itens", List.of(new java.util.LinkedHashMap<>(Map.of("id", "I9", "produtoId", "306")))));

        Map<String, Object> patched = runtime.invoke("Expedicao", "GerarDemanda", draft, ctx);

        // FORM P3-T5c: the invoked name is a flow now, so the answer is exactly the flow's return value
        // (the old "scratch targets patch the draft" shape belonged to procedures and is gone).
        assertEquals(Map.of("clienteEcho", "Alimentos"), patched, "the flow's return value is the whole answer");
        assertNull(patched.get("input"), "internal input echo is stripped from the returned draft");
        // invoke must NOT persist — the store is untouched until the user commits.
        assertEquals(0, store.count("Expedicao"), "invoke does not persist the root");
        assertEquals(0, store.count("ExpedicaoItem"), "invoke does not persist children");

        // Unknown procedure / aggregate are rejected distinctly.
        assertThrows(IllegalArgumentException.class, () -> runtime.invoke("Expedicao", "Nope", draft, ctx));
        assertThrows(IllegalArgumentException.class, () -> runtime.invoke("Nope", "GerarDemanda", draft, ctx));
    }

    /**
     * Pigmentampas friction #28: a procedure ending in a {@code return} step answers with just that
     * value -- its scratch targets and the echoed draft never ride back (PaintMosaicFromPhoto sent
     * ~206 KB per call, the photo, query results and the old cells included, for a 48 KB result).
     */
    @Test
    void invokeOfAProcedureWithAReturnStepAnswersWithOnlyTheReturnedValue() throws Exception {
        CompiledModel model = compiledModel();
        AggregateRuntime runtime = new AggregateRuntime(model, new Store(), wired(model, new Store()));
        ExecutionContext ctx = ExecutionContext.anonymous();
        Map<String, Object> draft = new java.util.LinkedHashMap<>(Map.of("id", "E9", "cliente", "Alimentos"));

        Map<String, Object> proposed = runtime.invoke("Expedicao", "ProporDraft", draft, ctx);
        assertEquals(draft, proposed, "a map return is the whole answer -- no scratch target, no 'return' wrapper");

        Map<String, Object> scalar = runtime.invoke("Expedicao", "LerCliente", draft, ctx);
        assertEquals(Map.of("return", "Alimentos"), scalar, "a non-map return comes back under 'return' alone");
    }

    private static ProcedureRunner wired(CompiledModel model, ConceptGateway gateway) {
        ProcedureRunner runner = new ProcedureRunner(model, gateway);
        InProcessAcceptanceRunner.attachKernel(runner, model, null, gateway, null);
        return runner;
    }
}
