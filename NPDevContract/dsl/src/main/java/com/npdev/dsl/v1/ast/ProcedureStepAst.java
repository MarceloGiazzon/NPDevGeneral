package com.npdev.dsl.v1.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ProcedureStepAst(
        String name,
        String type,
        String target,
        Object value,
        String condition,
        String items,
        String as,
        String concept,
        String query,
        Map<String, Object> data,
        String id,
        String procedure,
        String flow,
        String capability,
        String operation,
        String event,
        Map<String, Object> args,
        List<ProcedureStepAst> thenSteps,
        List<ProcedureStepAst> elseSteps,
        List<ProcedureStepAst> steps,
        Boolean trace,
        Boolean audit,
        Map<String, Object> metadata,
        Map<String, Object> set,
        Boolean createIfMissing,
        Map<String, Object> select,
        Object left,
        Object right
) {
    public ProcedureStepAst {
        data = copyMap(data);
        args = copyMap(args);
        thenSteps = thenSteps == null ? List.of() : List.copyOf(thenSteps);
        elseSteps = elseSteps == null ? List.of() : List.copyOf(elseSteps);
        steps = steps == null ? List.of() : List.copyOf(steps);
        metadata = copyMap(metadata);
        set = copyMap(set);
        createIfMissing = createIfMissing != null && createIfMissing;
        select = copyMap(select);
    }

    /**
     * npdev-procedure-step-null-tolerant-maps: Map.copyOf/Map.ofEntries reject null values, but a
     * step field like patchConcept.set can legitimately declare an explicit JSON null (e.g.
     * clearing a reference back to null) -- see REG-224. Same null-tolerant copy pattern as
     * ConceptRecord.copyData. CompiledProcedureStep (the compiled-model twin this AST record
     * compiles into) carries the identical fix under the same token -- keep them in sync.
     */
    private static Map<String, Object> copyMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
