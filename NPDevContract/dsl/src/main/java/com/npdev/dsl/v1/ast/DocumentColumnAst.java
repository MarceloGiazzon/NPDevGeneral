package com.npdev.dsl.v1.ast;

/**
 * REP-1: one column of a {@code kind: list} document -- a property of the document's concept, an
 * optional printed {@code label} (defaults to the field name), and an optional {@code total}
 * function ({@code sum|count|min|max|avg}) that feeds the report's subtotal and grand-total rows.
 */
public record DocumentColumnAst(String field, String label, String total) {
}
