package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * S7 Phase B (B13 declarative conversion vocabulary) + S8 W1.2 (roadmap deferred item #4): an
 * author-facing data-migration operation that the compiler resolves and the generator compiles to
 * the SAME {@code db/conversion-hooks/<id>/{hook.json,convert.sql}} shape {@code
 * ConversionHookRunner} already executes at boot -- one execution path, not two (see {@code
 * NPDevRuntimeHost/.../db/ConversionHookRunner.java}).
 *
 * <p>{@code op} is a closed enum ({@code copy}/{@code split}/{@code lookup}/{@code merge}/
 * {@code convert}); which of {@code from}/{@code to}/{@code into}/{@code match}/{@code set}/
 * {@code mergeFrom}/{@code with} are required depends on {@code op} and is enforced by the schema's
 * {@code allOf}/{@code if}/{@code then} blocks, then re-checked by the compiler against the actual
 * concept/field graph (a field the schema cannot see, e.g. a typo'd field name, must still be a
 * named compile error -- the X0 rule this vocabulary's own spec names).
 *
 * <p>S8 W1.2 adds two ops, each reusing an EXISTING sibling shape rather than inventing a new one:
 * <ul>
 *   <li>{@code merge} is {@code split}'s inverse -- N source fields ({@code mergeFrom}, the JSON
 *       {@code "from"} key as an ARRAY, distinct from the plain-string {@code from} the other ops
 *       use) concatenated with a separator ({@code with}, optional, defaults to {@code ""}) into one
 *       new {@code to} field.</li>
 *   <li>{@code convert} is {@code copy} with an explicit {@code CAST} instead of a bare assignment --
 *       {@code to}'s own declared field type (already resolved by the compiler exactly like
 *       {@code copy}'s {@code to}) is the conversion target type; no separate {@code toType}
 *       property exists because it would just be a second, driftable source of truth for
 *       information {@code to}'s own declared type already carries.</li>
 * </ul>
 */
public record ConversionAst(
        String id,
        String concept,
        String op,
        String from,
        String to,
        List<ConversionSplitTargetAst> into,
        ConversionLookupMatchAst match,
        String set,
        List<String> mergeFrom,
        String with
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
        mergeFrom = mergeFrom == null ? List.of() : List.copyOf(mergeFrom);
    }
}
