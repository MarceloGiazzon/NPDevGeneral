package com.npdev.dsl.v1.ast;

/** S7 Phase B: one target field of a {@code split} conversion -- {@code take} is a closed enum of
 *  the portable ANSI-SQL substring shapes the compiler knows how to emit. */
public record ConversionSplitTargetAst(String field, String take) {
    public ConversionSplitTargetAst {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("conversion split target field must be non-blank");
        }
        if (take == null || take.isBlank()) {
            throw new IllegalArgumentException("conversion split target take must be non-blank");
        }
    }
}
