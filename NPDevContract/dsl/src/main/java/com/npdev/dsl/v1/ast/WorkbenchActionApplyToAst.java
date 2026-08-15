package com.npdev.dsl.v1.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Move 7 W1 (docs/MOVE7_IMPLEMENTATION_SPEC.md): the {@code appendRow} shorthand for a {@link
 * WorkbenchActionAst}'s result -- folds the invoked procedure's output into {@code collection} by
 * mapping target field name to a source path in the procedure result (e.g. {@code
 * "localArmazenagemId": "$resultado.localArmazenagemId"}). Only {@code appendRow} is implemented;
 * anything more general belongs in the action's {@code afterAction} procedure instead.
 */
public record WorkbenchActionApplyToAst(String collection, String mode, Map<String, String> map) {
    public WorkbenchActionApplyToAst {
        // REG-146: was Map.copyOf(map) -- JDK's ImmutableCollections deliberately randomizes
        // iteration order per JVM run (a JEP 269 hash-flood mitigation), discarding the source
        // map's real insertion order and making compiled-model.json's applyTo.map serialization
        // nondeterministic between generation runs. Collections.unmodifiableMap over a fresh
        // LinkedHashMap preserves that order while staying just as immutable/defensively-copied.
        map = map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
