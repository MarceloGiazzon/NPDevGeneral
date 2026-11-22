package com.npdev.dsl.v1.compiled;

import java.util.Map;

public final class CompiledActionMetadata {
    private final String label;
    private final String confirmationText;
    private final String dangerLevel;
    private final String visibleWhen;
    private final String permissionHint;
    private final Map<String, String> labelLocales;

    public CompiledActionMetadata(
            String label,
            String confirmationText,
            String dangerLevel,
            String visibleWhen,
            String permissionHint
    ) {
        this(label, confirmationText, dangerLevel, visibleWhen, permissionHint, Map.of());
    }

    public CompiledActionMetadata(
            String label,
            String confirmationText,
            String dangerLevel,
            String visibleWhen,
            String permissionHint,
            Map<String, String> labelLocales
    ) {
        this.label = label;
        this.confirmationText = confirmationText;
        this.dangerLevel = dangerLevel;
        this.visibleWhen = visibleWhen;
        this.permissionHint = permissionHint;
        this.labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }

    public String getLabel() {
        return label;
    }

    public Map<String, String> getLabelLocales() {
        return labelLocales;
    }

    public String getConfirmationText() {
        return confirmationText;
    }

    public String getDangerLevel() {
        return dangerLevel;
    }

    public String getVisibleWhen() {
        return visibleWhen;
    }

    public String getPermissionHint() {
        return permissionHint;
    }
}
