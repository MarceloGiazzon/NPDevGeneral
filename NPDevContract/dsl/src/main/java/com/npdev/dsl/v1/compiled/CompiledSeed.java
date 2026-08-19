package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/**
 * R8.8: compiled form of {@link com.npdev.dsl.v1.ast.SeedAst} -- see that class's javadoc for the
 * full design rationale (why {@code concept} IS pack-qualified for a pack-declared seed, and why
 * pack ownership of the target concept is enforced eagerly at composition time rather than here).
 */
public record CompiledSeed(
        String concept,
        String alias,
        String id,
        Map<String, Object> data,
        Map<String, List<Integer>> repeatOverVars,
        Integer count
) {
    public CompiledSeed {
        data = data == null ? Map.of() : Map.copyOf(data);
        repeatOverVars = repeatOverVars == null ? Map.of() : Map.copyOf(repeatOverVars);
    }
}
