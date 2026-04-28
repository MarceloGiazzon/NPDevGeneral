package com.npdev.dsl.v1.compiled;

public final class CompiledDomainTypeUi {
    private final String label;
    private final String placeholder;
    private final String helpText;
    private final String widget;

    public CompiledDomainTypeUi(String label, String placeholder, String helpText, String widget) {
        this.label = label;
        this.placeholder = placeholder;
        this.helpText = helpText;
        this.widget = widget;
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
}
