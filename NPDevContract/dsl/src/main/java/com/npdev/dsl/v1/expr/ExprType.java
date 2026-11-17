package com.npdev.dsl.v1.expr;

import java.util.Locale;

/**
 * FORM P3-T2: the static type of an expression. {@link #ANY} is "cannot tell" (an unresolved reference, a
 * call the checker does not know, a store value) and is compatible with everything, so the checker only
 * ever reports a DEFINITE mismatch. {@link #NULL} is the {@code null} literal.
 */
public enum ExprType {
    ANY, NULL, NUMBER, TEXT, BOOLEAN, DATE, DATETIME, LIST;

    public boolean isTemporal() {
        return this == DATE || this == DATETIME;
    }

    /** True when a value of this type may flow where {@code other} is expected, ANY and NULL fitting everywhere. */
    public boolean fits(ExprType other) {
        if (this == ANY || this == NULL || other == ANY || other == NULL || this == other) {
            return true;
        }
        return isTemporal() && other.isTemporal();
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The expression type of a model field's declared DSL type, ANY when it is not a plain scalar. */
    public static ExprType fromDslType(String dslType) {
        if (dslType == null) {
            return ANY;
        }
        return switch (dslType.trim().toLowerCase(Locale.ROOT)) {
            case "integer", "int", "long", "decimal", "number", "double", "float", "money" -> NUMBER;
            case "string", "text", "uuid", "enum", "reference", "email", "url", "phone" -> TEXT;
            case "boolean", "bool" -> BOOLEAN;
            case "date" -> DATE;
            case "datetime", "timestamp", "instant" -> DATETIME;
            case "array" -> LIST;
            default -> ANY;
        };
    }
}
