package com.npdev.dsl.v1.compiled;

public final class CompiledInvariant {
    private final String ref;
    private final String type;
    private final String field;
    private final String expression;

    public CompiledInvariant(String ref, String type, String field, String expression) {
        this.ref = ref;
        this.type = type;
        this.field = field;
        this.expression = expression;
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
}
