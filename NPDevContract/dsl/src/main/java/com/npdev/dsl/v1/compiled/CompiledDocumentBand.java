package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/** R5.7: compiled form of {@link com.npdev.dsl.v1.ast.DocumentBandAst}. */
public record CompiledDocumentBand(
        String name,
        String kind,
        String collection,
        String label,
        Map<String, String> labelLocales,
        List<CompiledPanelFieldBinding> fields
) {
    public CompiledDocumentBand {
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
