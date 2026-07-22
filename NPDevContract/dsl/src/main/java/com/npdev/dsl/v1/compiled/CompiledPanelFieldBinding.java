package com.npdev.dsl.v1.compiled;

public record CompiledPanelFieldBinding(
        String field,
        String source,
        String visibleWhen,
        String enabledWhen,
        String readonlyWhen,
        CompiledPresentationMetadata ui,
        boolean editable
) {
}
