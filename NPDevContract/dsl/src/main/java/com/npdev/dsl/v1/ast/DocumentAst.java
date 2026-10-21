package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * REG-12 Slice 3: a declared {@code document} -- a PAGE-style kind (like {@link PanelAst}) bound to a
 * single concept's query, rendered server-side to PDF via {@code GET /{document}/render.pdf}. Columns
 * are NOT declared here: they're the same generator-computed {@code showInDefaultWebUi} set the
 * concept's grid already uses (see {@code BusinessUiEmitter.manifestList}), so a document's PDF and
 * its concept's grid can never drift out of sync with each other.
 *
 * <p><b>R5.7 (Roadmap Wave 1 2026-08-19):</b> the canonical ERP artifact -- an invoice with a header,
 * a line-item table, totals and a logo -- needs more structure than one flat concept grid. {@code
 * aggregate} optionally binds the document to a declared {@link AggregateAst} instead of (in addition
 * to) the flat {@code concept} query; {@code bands} then declares the header/line-item structure
 * against that aggregate's root and collections; {@code logo} binds an image field for the document
 * header. All three are optional and additive -- a pre-R5.7 document (bare {@code concept}, no
 * {@code aggregate}/{@code bands}/{@code logo}) keeps parsing, compiling, and rendering exactly as
 * before. When {@code aggregate} IS declared, {@code concept} must still be declared and must equal
 * the aggregate's root concept (enforced by {@code DocumentValidation}) -- {@code concept} stays the
 * one fact every document has always had, and {@code aggregate} is the added structure on top of it.
 *
 * <p><b>REP-1:</b> {@code kind: "list"} makes the document a multi-record REPORT over {@code concept}
 * rather than a one-record layout: {@code query} optionally names a plain {@code queries[]} entry
 * whose {@code where}/{@code orderBy}/{@code parameters} narrow the rows, {@code columns} picks and
 * labels the printed fields (each with an optional {@code total}), {@code groupBy} buckets the rows
 * by those concept fields, {@code subtotals} prints a total row per group and {@code grandTotal} a
 * final one. All six are optional and additive; a document without {@code kind} behaves exactly as
 * before.
 */
public record DocumentAst(
        String name,
        String concept,
        String title,
        String pageSize,
        Double marginMm,
        Map<String, Object> metadata,
        String aggregate,
        List<DocumentBandAst> bands,
        DocumentLogoAst logo,
        String kind,
        String query,
        List<DocumentColumnAst> columns,
        List<String> groupBy,
        Boolean subtotals,
        Boolean grandTotal
) {
    public DocumentAst {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        bands = bands == null ? List.of() : List.copyOf(bands);
        columns = columns == null ? List.of() : List.copyOf(columns);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
    }

    /** Pre-REP-1 9-arg shape (R5.7), kept so existing call sites keep compiling unchanged. */
    public DocumentAst(
            String name,
            String concept,
            String title,
            String pageSize,
            Double marginMm,
            Map<String, Object> metadata,
            String aggregate,
            List<DocumentBandAst> bands,
            DocumentLogoAst logo
    ) {
        this(name, concept, title, pageSize, marginMm, metadata, aggregate, bands, logo,
                null, null, List.of(), List.of(), null, null);
    }

    /** True for a {@code kind: list} multi-record report. */
    public boolean listReport() {
        return "list".equals(kind);
    }

    /** Pre-R5.7 6-arg shape, kept so existing call sites (hand-built fixtures, older tests) that
     *  construct a flat concept-bound document with no aggregate/bands/logo keep compiling unchanged. */
    public DocumentAst(
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
