package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * Compiled form of {@link com.npdev.dsl.v1.ast.FieldPickerAst}. {@code filter} is already the
 * FULLY RESOLVED predicate (a referenced selector's own {@code filter} AND-composed with any local
 * {@code filter}, when {@code selectorRef} was declared) -- a downstream emitter never needs to know
 * a selector was involved. {@code displayFields}/{@code searchFields}/{@code orderBy} are empty
 * unless {@code selectorRef} resolved to a real selector (REAL_LIFT_PLAN_2026-09-03 package C2,
 * boundary B16 Step 2, EDIT-18); a downstream emitter falls back to its own inference when empty.
 */
public record CompiledFieldPicker(
        String filter, boolean multiSelect,
        List<String> displayFields, List<String> searchFields, List<String> orderBy
) {
    public CompiledFieldPicker {
        displayFields = displayFields == null ? List.of() : List.copyOf(displayFields);
        searchFields = searchFields == null ? List.of() : List.copyOf(searchFields);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
    }

    public CompiledFieldPicker(String filter, boolean multiSelect) {
        this(filter, multiSelect, List.of(), List.of(), List.of());
    }
}
