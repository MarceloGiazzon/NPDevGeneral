package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * S7 Phase B (B13 declarative conversion vocabulary): an author-facing data-migration operation
 * that the compiler resolves and the generator compiles to the SAME {@code db/conversion-hooks/
 * <id>/{hook.json,convert.sql}} shape {@code ConversionHookRunner} already executes at boot --
 * one execution path, not two (see {@code NPDevRuntimeHost/.../db/ConversionHookRunner.java}).
 *
 * <p>{@code op} is a closed enum ({@code copy}/{@code split}/{@code lookup}); which of
 * {@code from}/{@code to}/{@code into}/{@code match}/{@code set} are required depends on {@code op}
 * and is enforced by the schema's {@code allOf}/{@code if}/{@code then} blocks, then re-checked by
 * the compiler against the actual concept/field graph (a field the schema cannot see, e.g. a typo'd
 * field name, must still be a named compile error -- the X0 rule this vocabulary's own spec names).
 */
public record ConversionAst(
        String id,
        String concept,
        String op,
        String from,
        String to,
        List<ConversionSplitTargetAst> into,
        ConversionLookupMatchAst match,
        String set
) {
    public ConversionAst {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("conversion id must be non-blank");
        }
        if (concept == null || concept.isBlank()) {
            throw new IllegalArgumentException("conversion concept must be non-blank");
        }
        if (op == null || op.isBlank()) {
            throw new IllegalArgumentException("conversion op must be non-blank");
        }
        into = into == null ? List.of() : List.copyOf(into);
    }
}
