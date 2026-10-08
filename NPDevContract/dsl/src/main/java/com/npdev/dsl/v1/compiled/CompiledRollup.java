package com.npdev.dsl.v1.compiled;

/**
 * Compiled form of a concept's {@code rollups[]} entry ({@link com.npdev.dsl.v1.ast.RollupAst}): the
 * declaring concept's {@code field} is kept equal to {@code fn(of)} over the {@code from} rows whose
 * {@code via} reference points at it. Maintained by the RuntimeHost's
 * {@code RollupConceptStoreDecorator}.
 */
public record CompiledRollup(String field, String from, String via, String fn, String of) {

    public CompiledRollup {
        fn = fn == null || fn.isBlank() ? "count" : fn.trim();
        of = of == null || of.isBlank() ? null : of.trim();
    }
}
