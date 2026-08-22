package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/**
 * Wave 6 (RC-A1): compiled form of {@link com.npdev.dsl.v1.ast.PropertyAst}. See that class's
 * javadoc for the type/default/settableAt/securityRelevant contract this mirrors exactly.
 *
 * <p>R5.6: {@code labelLocales} is additive -- {@code label} keeps carrying the resolved default
 * text exactly as before (every existing caller of {@link #label()} sees no change), so the
 * 6-arg constructor below stays available for every caller built against the pre-R5.6 shape.
 */
public record CompiledProperty(
        String name,
        String type,
        Object defaultValue,
        List<String> settableAt,
        String label,
        boolean securityRelevant,
        Map<String, String> labelLocales
) {
    public CompiledProperty {
        settableAt = settableAt == null ? List.of() : List.copyOf(settableAt);
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }

    public CompiledProperty(String name, String type, Object defaultValue, List<String> settableAt, String label, boolean securityRelevant) {
        this(name, type, defaultValue, settableAt, label, securityRelevant, Map.of());
    }
}
