package com.npdev.dsl.v1.ast;

/**
 * P8 prelude (Pigmentampas Mosaic.likeCount): a STORED field on this concept that the platform keeps
 * equal to an aggregate over a child concept's rows ({@code rollups: [{ field, from, via, fn, of }]}).
 *
 * <p>{@code field} is a numeric field of the declaring (parent) concept; {@code from} is the child
 * concept; {@code via} is the child's reference field pointing at the parent; {@code fn} is one of
 * {@value #FN_COUNT} (default), {@code sum}, {@code min}, {@code max}, {@code avg}; {@code of} is the
 * child's numeric field the non-count functions read. The RuntimeHost recomputes it inside every
 * child write's transaction, so it is sortable and filterable like any column.
 */
public record RollupAst(String field, String from, String via, String fn, String of) {

    public static final String FN_COUNT = "count";

    public RollupAst {
        field = blankToNull(field);
        from = blankToNull(from);
        via = blankToNull(via);
        fn = fn == null || fn.isBlank() ? FN_COUNT : fn.trim();
        of = blankToNull(of);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
