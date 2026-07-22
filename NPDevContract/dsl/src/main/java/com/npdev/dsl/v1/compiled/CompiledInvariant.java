package com.npdev.dsl.v1.compiled;

import java.util.List;

public final class CompiledInvariant {
    private final String ref;
    private final String type;
    private final String field;
    private final String expression;
    private final List<String> fields;

    public CompiledInvariant(String ref, String type, String field, String expression) {
        this(ref, type, field, expression, field == null ? List.of() : List.of(field));
    }

    /** LIFT-UNIQUE-P1: {@code fields} is the ordered field list for compound-capable invariants
     * (e.g. {@code unique}); {@code field} remains the first entry for single-field callers. */
    public CompiledInvariant(String ref, String type, String field, String expression, List<String> fields) {
        this.ref = ref;
        this.type = type;
        this.field = field;
        this.expression = expression;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public String getRef() {
        return ref;
    }

    public String getType() {
        return type;
    }

    public String getField() {
        return field;
    }

    public String getExpression() {
        return expression;
    }

    /** Ordered field list. For a single-field invariant this is a 1-element list equal to {@link #getField()}. */
    public List<String> getFields() {
        return fields;
    }
}
