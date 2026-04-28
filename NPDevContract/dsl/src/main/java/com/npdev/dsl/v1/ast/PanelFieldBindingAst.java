package com.npdev.dsl.v1.ast;

public record PanelFieldBindingAst(
        String field,
        String source,
        String visibleWhen,
        String enabledWhen,
        String readonlyWhen,
        PresentationMetadataAst ui
) {
}
