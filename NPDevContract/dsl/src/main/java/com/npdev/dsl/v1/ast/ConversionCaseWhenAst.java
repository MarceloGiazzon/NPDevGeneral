package com.npdev.dsl.v1.ast;

/** Wave 4 (B13 vocabulary expansion): one branch of a {@code case} conversion -- {@code equals} is
 *  the literal source value to match, {@code then} the literal to write when it matches. Both are
 *  plain string literals, never field references, same discipline as {@code with} on {@code merge}. */
public record ConversionCaseWhenAst(String equals, String then) {
    public ConversionCaseWhenAst {
        if (equals == null || equals.isBlank()) {
            throw new IllegalArgumentException("conversion case 'when' clause 'equals' must be non-blank");
        }
        if (then == null) {
            throw new IllegalArgumentException("conversion case 'when' clause 'then' must be present");
        }
    }
}
