package com.npdev.dsl.v1.ast;

import java.util.Map;

public final class EnumOptionAst {
    private final String value;
    private final String label;
    private final Integer order;
    private final String group;
    private final boolean defaultValue;
    private final boolean deprecated;
    private final String iconHint;
    private final String badgeHint;
    private final String description;
    private final Map<String, String> labelLocales;

    public EnumOptionAst(
            String value,
            String label,
            Integer order,
            String group,
            boolean defaultValue,
            boolean deprecated,
            String iconHint,
            String badgeHint,
            String description
    ) {
        this(value, label, order, group, defaultValue, deprecated, iconHint, badgeHint, description, Map.of());
    }

    public EnumOptionAst(
            String value,
            String label,
            Integer order,
            String group,
            boolean defaultValue,
            boolean deprecated,
            String iconHint,
            String badgeHint,
            String description,
            Map<String, String> labelLocales
    ) {
        this.value = value;
        this.label = label;
        this.order = order;
        this.group = group;
        this.defaultValue = defaultValue;
        this.deprecated = deprecated;
        this.iconHint = iconHint;
        this.badgeHint = badgeHint;
        this.description = description;
        this.labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    public Integer getOrder() {
        return order;
    }

    public String getGroup() {
        return group;
    }

    public boolean isDefaultValue() {
        return defaultValue;
    }

    public boolean isDeprecated() {
        return deprecated;
    }

    public String getIconHint() {
        return iconHint;
    }

    public String getBadgeHint() {
        return badgeHint;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, String> getLabelLocales() {
        return labelLocales;
    }
}
