package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * S7 Phase B (B13 declarative conversion vocabulary): the compiled, field-resolved form of a
 * {@code conversions[]} entry -- every {@code concept}/field reference has already been checked
 * against the real model graph by {@code ModelCompiler} (a reference the compiler cannot resolve is
 * a compile error, never a silently-dropped conversion). The generator compiles this into the
 * {@code db/conversion-hooks/<id>/{hook.json,convert.sql}} shape {@code ConversionHookRunner}
 * already executes at boot.
 */
public record CompiledConversion(
        String id,
        String concept,
        String op,
        String from,
        String to,
        List<CompiledConversionSplitTarget> into,
        CompiledConversionLookupMatch match,
        String set
) {
    public CompiledConversion {
        into = into == null ? List.of() : List.copyOf(into);
    }

    public record CompiledConversionSplitTarget(String field, String take) {
    }

    public record CompiledConversionLookupMatch(String concept, String on, String equals) {
    }
}
