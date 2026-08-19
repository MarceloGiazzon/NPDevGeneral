package com.npdev.dsl.v1.compiled;

import java.util.Map;

public final class CompiledDomainTypeUi {
    private final String label;
    private final String placeholder;
    private final String helpText;
    private final String widget;
    private final Map<String, String> labelLocales;

    public CompiledDomainTypeUi(String label, String placeholder, String helpText, String widget) {
        this(label, placeholder, helpText, widget, Map.of());
    }

    public CompiledDomainTypeUi(String label, String placeholder, String helpText, String widget, Map<String, String> labelLocales) {
        this.label = label;
        this.placeholder = placeholder;
        this.helpText = helpText;
        this.widget = widget;
        this.labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }

    public String getLabel() {
        return label;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public String getHelpText() {
        return helpText;
    }

    public String getWidget() {
        return widget;
    }

    public Map<String, String> getLabelLocales() {
        return labelLocales;
    }
}
