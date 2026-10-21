package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

/** REG-12 Slice 3: compiled form of {@link com.npdev.dsl.v1.ast.DocumentAst}. R5.7 adds the
 *  optional {@code aggregate}/{@code bands}/{@code logo} triple -- see the AST record's javadoc.
 *  REP-1 adds the {@code kind: list} report members ({@code query}/{@code columns}/{@code groupBy}/
 *  {@code subtotals}/{@code grandTotal}). */
public record CompiledDocument(
        String name,
        String concept,
        String title,
        String pageSize,
        Double marginMm,
        Map<String, Object> metadata,
        String aggregate,
        List<CompiledDocumentBand> bands,
        CompiledDocumentLogo logo,
        String kind,
        String query,
        List<CompiledDocumentColumn> columns,
        List<String> groupBy,
        Boolean subtotals,
        Boolean grandTotal
) {
    public CompiledDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        bands = bands == null ? List.of() : List.copyOf(bands);
        columns = columns == null ? List.of() : List.copyOf(columns);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
    }

    /** Pre-REP-1 9-arg shape (R5.7), kept so existing call sites keep compiling unchanged. */
    public CompiledDocument(
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
        this(name, concept, title, pageSize, marginMm, metadata, aggregate, bands, logo,
                null, null, List.of(), List.of(), null, null);
    }

    /** True for a {@code kind: list} multi-record report (REP-1). */
    public boolean listReport() {
        return "list".equals(kind);
    }

    public boolean subtotalsEnabled() {
        return Boolean.TRUE.equals(subtotals);
    }

    public boolean grandTotalEnabled() {
        return Boolean.TRUE.equals(grandTotal);
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
