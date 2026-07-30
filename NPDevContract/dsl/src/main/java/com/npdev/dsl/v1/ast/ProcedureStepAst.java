package com.npdev.dsl.v1.ast;

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
        data = data == null ? Map.of() : Map.copyOf(data);
        args = args == null ? Map.of() : Map.copyOf(args);
        thenSteps = thenSteps == null ? List.of() : List.copyOf(thenSteps);
        elseSteps = elseSteps == null ? List.of() : List.copyOf(elseSteps);
        steps = steps == null ? List.of() : List.copyOf(steps);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        set = set == null ? Map.of() : Map.copyOf(set);
        createIfMissing = createIfMissing != null && createIfMissing;
        select = select == null ? Map.of() : Map.copyOf(select);
    }
}
