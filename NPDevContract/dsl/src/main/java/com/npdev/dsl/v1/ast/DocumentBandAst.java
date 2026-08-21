package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * R5.7 (Roadmap Wave 1 2026-08-19): one printable band of a {@link DocumentAst} bound to an {@code
 * aggregate} -- either the {@code header} band (fields read off the aggregate root, e.g. an invoice's
 * customer/date/number block) or a {@code lineItems} band (rows read off one of the aggregate's
 * declared {@code collections}, e.g. an invoice's line-item table). This is the "bands, line items"
 * half of the ERP-document primitive: a header plus one or more repeating collections, all bound to
 * the SAME nested tree {@code AggregateRuntime.load()} already produces at runtime -- a document does
 * not re-query per band, it walks that tree.
 *
 * <p>{@code fields} deliberately reuses {@link PanelFieldBindingAst} rather than inventing a parallel
 * binding vocabulary -- an author who already knows how to bind a panel field (a bare property-name
 * string, optional {@code ui} presentation hints for label/widget/format) binds a document band field
 * the identical way. {@code source}/{@code editable}/{@code enabledWhen}/{@code readonlyWhen} on the
 * reused shape are inert for a document (a rendered PDF has no interactive/editable state) but stay
 * part of the record rather than being stripped, so the SAME field-binding literal an author writes
 * for a panel can be copy-pasted into a document band unmodified.
 *
 * <p>{@code fields} must be non-empty -- see {@code DocumentValidation.validateDocuments}: a band
 * with zero field bindings would render a table with no columns (or a header with nothing in it),
 * which is silently indistinguishable from "the data is empty" and is exactly the "blank page instead
 * of an authoring error" failure mode R5.7 calls out. That is caught at compile/validate time, not
 * discovered by a customer looking at a printed invoice.
 */
public record DocumentBandAst(
        String name,
        String kind,
        String collection,
        String label,
        Map<String, String> labelLocales,
        List<PanelFieldBindingAst> fields
) {
    public DocumentBandAst {
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
