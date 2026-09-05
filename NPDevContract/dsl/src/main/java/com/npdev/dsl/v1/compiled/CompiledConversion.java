package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * S7 Phase B (B13 declarative conversion vocabulary) + S8 W1.2 (roadmap deferred item #4): the
 * compiled, field-resolved form of a {@code conversions[]} entry -- every {@code concept}/field
 * reference has already been checked against the real model graph by {@code ModelCompiler} (a
 * reference the compiler cannot resolve is a compile error, never a silently-dropped conversion).
 * The generator compiles this into the {@code db/conversion-hooks/<id>/{hook.json,convert.sql}}
 * shape {@code ConversionHookRunner} already executes at boot. {@code mergeFrom}/{@code with} back
 * {@code merge}; {@code convert} reuses {@code from}/{@code to} (see {@code ConversionAst}'s own
 * javadoc for why it has no separate {@code toType}). Wave 4 (B13 vocabulary expansion):
 * {@code coalesce} reuses {@code mergeFrom}/{@code to} (no {@code with} -- nothing is joined);
 * {@code case} reuses {@code from}/{@code to} plus {@code when}/{@code elseValue}.
 *
 * <p>B1 (REAL_LIFT_PLAN_2026-09-03, B13): {@code javaHook} is a sibling alternative to {@code op}
 * (see {@code ConversionAst}'s own javadoc) -- the emitter compiles it to {@code hook.json}'s
 * {@code javaHook} object instead of a sibling {@code convert.sql}, and {@code claims} (author-
 * declared, since a Java hook is a black box to automatic claim derivation) into the identical
 * {@code ADD_REQUIRED_COLUMN:<table>:<col>} claim-key format every other op already produces.
 */
public record CompiledConversion(
        String id,
        String concept,
        String op,
        String from,
        String to,
        List<CompiledConversionSplitTarget> into,
        CompiledConversionLookupMatch match,
        String set,
        List<String> mergeFrom,
        String with,
        List<CompiledConversionCaseWhen> when,
        String elseValue,
        CompiledJavaHook javaHook,
        List<String> claims
) {
    public CompiledConversion {
        into = into == null ? List.of() : List.copyOf(into);
        mergeFrom = mergeFrom == null ? List.of() : List.copyOf(mergeFrom);
        when = when == null ? List.of() : List.copyOf(when);
        claims = claims == null ? List.of() : List.copyOf(claims);
    }

    public record CompiledConversionSplitTarget(String field, String take) {
    }

    public record CompiledConversionLookupMatch(String concept, String on, String equals) {
    }

    public record CompiledConversionCaseWhen(String equals, String then) {
    }

    /** B1 (B13): the compiled, field-resolved form of {@code ConversionAst.JavaHookAst}. */
    public record CompiledJavaHook(String source, String className, String method) {
    }
}
