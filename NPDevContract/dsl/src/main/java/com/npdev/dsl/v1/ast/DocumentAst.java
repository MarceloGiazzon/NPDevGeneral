package com.npdev.dsl.v1.ast;

import java.util.Map;

/**
 * REG-12 Slice 3: a declared {@code document} -- a PAGE-style kind (like {@link PanelAst}) bound to a
 * single concept's query, rendered server-side to PDF via {@code GET /{document}/render.pdf}. Columns
 * are NOT declared here: they're the same generator-computed {@code showInDefaultWebUi} set the
 * concept's grid already uses (see {@code BusinessUiEmitter.manifestList}), so a document's PDF and
 * its concept's grid can never drift out of sync with each other.
 */
public record DocumentAst(
        String name,
        String concept,
        String title,
        String pageSize,
        Double marginMm,
        Map<String, Object> metadata
) {
    public DocumentAst {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
