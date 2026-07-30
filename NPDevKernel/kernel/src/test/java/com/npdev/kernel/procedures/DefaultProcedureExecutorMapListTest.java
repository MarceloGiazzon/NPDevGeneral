package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A / Gap 6): {@code mapList} is a list
 * transform/comprehension -- unlike {@code forEach}, which only iterates for side effects, it
 * PRODUCES a new list, one output object per input item. {@code select} resolves each field via
 * the same literal-vs-{@code $ref} convention {@code patchConcept}'s {@code set} already
 * established (proven directly here, not just asserted in a comment).
 */
class DefaultProcedureExecutorMapListTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final EventBus NOOP_EVENT_BUS = event -> { };

    private static DefaultProcedureExecutor newExecutor() {
        ConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        return new DefaultProcedureExecutor(gateway, (call, state) -> CapabilityResult.success(null), NOOP_EVENT_BUS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapListProducesOneOutputObjectPerItemResolvingRefsAndLiterals() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "MapParsedLines",
                List.of(
                        ProcedureStep.mapList(
                                "map-lines", "$parsed.itens", "linha",
                                Map.of(
                                        "produtoId", "$linha.codigo",
                                        "quantidade", "$linha.qtd",
                                        "origem", "import" // a plain value is a literal, same as patchConcept's set
                                ),
                                "itensMapeados"
                        ),
                        ProcedureStep.returnValue("return-mapped", "$itensMapeados")
                )
        );

        ProcedureExecutionResult result = executor.execute(
                definition,
                Map.of("parsed", Map.of("itens", List.of(
                        Map.of("codigo", "SKU-1", "qtd", 3),
                        Map.of("codigo", "SKU-2", "qtd", 7)
                ))),
                CTX);

        assertTrue(result.ok(), "mapList over a well-formed collection must succeed: " + result.failureMessage());
        List<Map<String, Object>> mapped = (List<Map<String, Object>>) result.state().get("itensMapeados");
        assertEquals(2, mapped.size(), "one output object per input item");
        assertEquals("SKU-1", mapped.get(0).get("produtoId"), "a $-prefixed value is a ref into the current loop item");
        assertEquals(3, mapped.get(0).get("quantidade"));
        assertEquals("import", mapped.get(0).get("origem"), "a plain value is a literal, passed through verbatim");
        assertEquals("SKU-2", mapped.get(1).get("produtoId"));
        assertEquals(7, mapped.get(1).get("quantidade"));
        assertEquals("import", mapped.get(1).get("origem"));
    }

    @Test
    void mapListFailsWhenTheCollectionRefDoesNotResolveToAnIterable() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "MapMissingCollection",
                List.of(ProcedureStep.mapList(
                        "map-lines", "$parsed.itens", "linha", Map.of("a", "$linha.b"), "out"))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertFalse(result.ok(), "mapList with no resolvable collection must fail, not silently produce an empty list");
        assertEquals("COLLECTION_REQUIRED", result.failureCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapListOverAnEmptyCollectionProducesAnEmptyListSuccessfully() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "MapEmptyCollection",
                List.of(ProcedureStep.mapList(
                        "map-lines", "$parsed.itens", "linha", Map.of("a", "$linha.b"), "out"))
        );

        ProcedureExecutionResult result = executor.execute(
                definition, Map.of("parsed", Map.of("itens", List.of())), CTX);

        assertTrue(result.ok(), result.failureMessage());
        List<Map<String, Object>> mapped = (List<Map<String, Object>>) result.state().get("out");
        assertTrue(mapped.isEmpty(), "an empty input collection produces an empty (not null/missing) output list");
    }

    @Test
    void mapListRestoresThePreviousAsBindingAfterCompletion() {
        DefaultProcedureExecutor executor = newExecutor();
        ProcedureDefinition definition = new ProcedureDefinition(
                "MapLinesReusingLoopVar",
                List.of(
                        ProcedureStep.mapList(
                                "map-lines", "$parsed.itens", "linha", Map.of("id", "$linha.id"), "out"),
                        ProcedureStep.returnValue("return-linha", "$linha")
                )
        );

        ProcedureExecutionResult result = executor.execute(
                definition,
                Map.of(
                        "linha", "outer-value",
                        "parsed", Map.of("itens", List.of(Map.of("id", "x")))
                ),
                CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals("outer-value", result.state().get("return"),
                "mapList must restore any prior binding of its loop variable name, not leak the last item");
    }
}
