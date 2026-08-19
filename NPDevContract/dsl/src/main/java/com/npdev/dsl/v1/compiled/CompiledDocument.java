package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/** REG-12 Slice 3: compiled form of {@link com.npdev.dsl.v1.ast.DocumentAst}. R5.7 adds the
 *  optional {@code aggregate}/{@code bands}/{@code logo} triple -- see the AST record's javadoc. */
public record CompiledDocument(
        String name,
        String concept,
        String title,
        String pageSize,
        Double marginMm,
        Map<String, Object> metadata,
        String aggregate,
        List<CompiledDocumentBand> bands,
        CompiledDocumentLogo logo
) {
    public CompiledDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        bands = bands == null ? List.of() : List.copyOf(bands);
    }

    /** Pre-R5.7 6-arg shape, kept for existing call sites building a flat concept-bound document. */
    public CompiledDocument(
            String name,
            String concept,
            String title,
            String pageSize,
            Double marginMm,
            Map<String, Object> metadata
    ) {
        this(name, concept, title, pageSize, marginMm, metadata, null, List.of(), null);
    }
}
