package com.npdev.dsl.v1.compiled;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        Map<String, Object> metadata,
        Map<String, Object> set,
        Boolean createIfMissing,
        Map<String, Object> select
) {
    public CompiledProcedureStep {
        data = data == null ? Map.of() : Map.copyOf(data);
        // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A): args -- UNLIKE every other Map field
        // on this record -- is iterated POSITIONALLY: ProcedureRunner/NPDevCliMain's toProcedureStep
        // build a callCapability's argRefs from args.values() to reflectively invoke a multi-param
        // Java capability method by position (ArtifactLocalJavaSourceCapabilityHandler). Map.copyOf
        // (used for every sibling field below, all consumed by KEY not position) returns a JDK
        // ImmutableCollections.MapN whose iteration order is EXPLICITLY UNSPECIFIED and reshuffled
        // by a fresh per-JVM-run random salt -- so a multi-entry args map would silently scramble
        // argument order on roughly half of all app restarts. Found the hard way: a 3-arg
        // callCapability (mapList-populated produtosConhecidos/chavesJaImportadas into
        // ParseNfeProcedure) worked on some app starts and failed with a reflection "argument type
        // mismatch" on others, from the exact same jar. An unmodifiable LinkedHashMap preserves the
        // insertion order the caller already normalized (ModelCompiler's alphabetical sortObjectMap,
        // itself needed only for canonical-JSON determinism, not because alphabetical order is
        // special -- any Java author declaring a multi-arg capability method must declare its
        // parameters in THAT alphabetical-by-args-key order, documented on the method itself).
        args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        thenSteps = thenSteps == null ? List.of() : List.copyOf(thenSteps);
        elseSteps = elseSteps == null ? List.of() : List.copyOf(elseSteps);
        steps = steps == null ? List.of() : List.copyOf(steps);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        set = set == null ? Map.of() : Map.copyOf(set);
        createIfMissing = createIfMissing != null && createIfMissing;
        select = select == null ? Map.of() : Map.copyOf(select);
    }
}
