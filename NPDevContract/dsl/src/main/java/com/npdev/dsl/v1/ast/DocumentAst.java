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
        DocumentLogoAst logo
) {
    public DocumentAst {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        bands = bands == null ? List.of() : List.copyOf(bands);
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
