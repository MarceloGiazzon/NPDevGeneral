package com.npdev.dsl.v1.compiled;

public final class CompiledEnumOption {
    private final String value;
    private final String label;
    private final Integer order;
    private final String group;
    private final boolean defaultValue;
    private final boolean deprecated;
    private final String iconHint;
    private final String badgeHint;
    private final String description;

    public CompiledEnumOption(
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
        this.value = value;
        this.label = label;
        this.order = order;
        this.group = group;
        this.defaultValue = defaultValue;
        this.deprecated = deprecated;
        this.iconHint = iconHint;
        this.badgeHint = badgeHint;
        this.description = description;
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
}
