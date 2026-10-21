package com.npdev.dsl.v1.compiled;

/** REP-1: compiled form of {@link com.npdev.dsl.v1.ast.DocumentColumnAst}. */
public record CompiledDocumentColumn(String field, String label, String total) {
}
