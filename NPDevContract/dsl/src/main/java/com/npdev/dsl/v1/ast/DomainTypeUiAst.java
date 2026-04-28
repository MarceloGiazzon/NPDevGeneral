package com.npdev.dsl.v1.ast;

public final class DomainTypeUiAst {
    private final String label;
    private final String placeholder;
    private final String helpText;
    private final String widget;

    public DomainTypeUiAst(String label, String placeholder, String helpText, String widget) {
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
