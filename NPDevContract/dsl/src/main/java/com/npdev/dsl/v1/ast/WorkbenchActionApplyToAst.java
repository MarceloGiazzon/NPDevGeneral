package com.npdev.dsl.v1.ast;

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
        map = map == null ? Map.of() : Map.copyOf(map);
    }
}
