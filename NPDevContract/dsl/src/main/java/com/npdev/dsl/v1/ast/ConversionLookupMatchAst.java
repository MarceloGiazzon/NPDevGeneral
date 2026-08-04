package com.npdev.dsl.v1.ast;

/** S7 Phase B: a {@code lookup} conversion's join target -- match {@code concept} on its
 *  {@code on} field against this concept's own {@code equals} field, and write the matched row's
 *  id into the conversion's {@code set} field. */
public record ConversionLookupMatchAst(String concept, String on, String equals) {
    public ConversionLookupMatchAst {
        if (concept == null || concept.isBlank()) {
            throw new IllegalArgumentException("conversion lookup match concept must be non-blank");
        }
        if (on == null || on.isBlank()) {
            throw new IllegalArgumentException("conversion lookup match 'on' field must be non-blank");
        }
        if (equals == null || equals.isBlank()) {
            throw new IllegalArgumentException("conversion lookup match 'equals' field must be non-blank");
        }
    }
}
