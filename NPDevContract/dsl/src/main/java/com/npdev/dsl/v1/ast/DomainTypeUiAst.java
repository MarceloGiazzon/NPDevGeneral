package com.npdev.dsl.v1.ast;

import java.util.Map;

public final class DomainTypeUiAst {
    private final String label;
    private final String placeholder;
    private final String widget;
    private final Map<String, String> labelLocales;

    public DomainTypeUiAst(String label, String placeholder, String widget, Map<String, String> labelLocales) {
        this.label = label;
        this.placeholder = placeholder;
        this.widget = widget;
        this.labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }

    public String getLabel() {
        return label;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public String getWidget() {
        return widget;
    }

    public Map<String, String> getLabelLocales() {
        return labelLocales;
    }
}
