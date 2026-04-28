package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

public record CompiledProcedureStep(
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
        List<CompiledProcedureStep> thenSteps,
        List<CompiledProcedureStep> elseSteps,
        List<CompiledProcedureStep> steps,
        Boolean trace,
        Boolean audit,
        Map<String, Object> metadata
) {
    public CompiledProcedureStep {
        data = data == null ? Map.of() : Map.copyOf(data);
        args = args == null ? Map.of() : Map.copyOf(args);
        thenSteps = thenSteps == null ? List.of() : List.copyOf(thenSteps);
        elseSteps = elseSteps == null ? List.of() : List.copyOf(elseSteps);
        steps = steps == null ? List.of() : List.copyOf(steps);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
