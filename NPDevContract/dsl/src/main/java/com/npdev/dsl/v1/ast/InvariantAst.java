package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InvariantAst {
    private final String name;
    private final String type;
    private final List<String> fields;
    private final String expression;
    private final String specializesName;
    private final boolean override;

    public InvariantAst(String type, List<String> fields) {
        this(null, type, fields, null, null, false);
    }

    public InvariantAst(String type, List<String> fields, String expression) {
        this(null, type, fields, expression, null, false);
    }

    public InvariantAst(String name, String type, List<String> fields, String expression) {
        this(name, type, fields, expression, null, false);
    }

    public InvariantAst(
            String name,
            String type,
            List<String> fields,
            String expression,
            String specializesName,
            boolean override
    ) {
        this.name = name;
        this.type = type;
        this.fields = new ArrayList<>(fields);
        this.expression = expression;
        this.specializesName = specializesName;
        this.override = override;
    }

    public String getName() { return name; }

    public String getType() { return type; }

    public List<String> getFields() {
        return Collections.unmodifiableList(fields);
    }

    public String getExpression() { return expression; }

    public String getSpecializesName() {
        return specializesName;
    }

    public boolean isOverride() {
        return override;
    }
}
