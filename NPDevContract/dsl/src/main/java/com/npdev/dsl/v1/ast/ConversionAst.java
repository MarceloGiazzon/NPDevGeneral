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
 * {@code convert}/{@code coalesce}/{@code case}); which of {@code from}/{@code to}/{@code into}/
 * {@code match}/{@code set}/{@code mergeFrom}/{@code with}/{@code when}/{@code elseValue} are
 * required depends on {@code op} and is enforced by the schema's {@code allOf}/{@code if}/
 * {@code then} blocks, then re-checked by the compiler against the actual concept/field graph (a
 * field the schema cannot see, e.g. a typo'd field name, must still be a named compile error -- the
 * X0 rule this vocabulary's own spec names).
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
 *
 * <p>Wave 4 (BOUNDARY_LIFT_PLAN_2026-09-02.md package 4.2, B13 vocabulary expansion) adds two more,
 * both plain ANSI SQL with no dialect-specific emission needed:
 * <ul>
 *   <li>{@code coalesce} reuses {@code merge}'s {@code mergeFrom} (2+ source fields) + {@code to}
 *       shape, but picks the first NON-NULL source value ({@code COALESCE(...)}) rather than
 *       concatenating all of them -- no {@code with} separator, since nothing is joined.</li>
 *   <li>{@code case} maps a source field's literal values to literal replacements: {@code from} +
 *       {@code to} (same shape {@code copy}/{@code convert} use) plus {@code when} (1+
 *       {@link ConversionCaseWhenAst} equals/then pairs, evaluated in declared order) and an
 *       optional {@code elseValue} (the JSON key is {@code "else"}; the Java name avoids the
 *       reserved word). A row matching no {@code when} clause and declaring no {@code elseValue} is
 *       left NULL by the generated {@code CASE WHEN ... END} -- the closing {@code SET NOT NULL}
 *       then fails the boot loudly on it, same X0 discipline as every other op here.</li>
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
        String with,
        List<ConversionCaseWhenAst> when,
        String elseValue
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
        when = when == null ? List.of() : List.copyOf(when);
    }
}
