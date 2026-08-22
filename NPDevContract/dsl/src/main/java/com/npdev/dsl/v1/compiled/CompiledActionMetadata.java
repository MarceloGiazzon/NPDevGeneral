package com.npdev.dsl.v1.compiled;

import java.util.Map;

public final class CompiledActionMetadata {
    private final String label;
    private final String confirmationText;
    private final String successMessage;
    private final String failureHint;
    private final String dangerLevel;
    private final String visibleWhen;
    private final String permissionHint;
    private final String inputFormHint;
    private final Map<String, String> labelLocales;

    public CompiledActionMetadata(
            String label,
            String confirmationText,
            String successMessage,
            String failureHint,
            String dangerLevel,
            String visibleWhen,
            String permissionHint,
            String inputFormHint
    ) {
        this(label, confirmationText, successMessage, failureHint, dangerLevel, visibleWhen, permissionHint, inputFormHint, Map.of());
    }

    public CompiledActionMetadata(
            String label,
            String confirmationText,
            String successMessage,
            String failureHint,
            String dangerLevel,
            String visibleWhen,
            String permissionHint,
            String inputFormHint,
            Map<String, String> labelLocales
    ) {
        this.label = label;
        this.confirmationText = confirmationText;
        this.successMessage = successMessage;
        this.failureHint = failureHint;
        this.dangerLevel = dangerLevel;
        this.visibleWhen = visibleWhen;
        this.permissionHint = permissionHint;
        this.inputFormHint = inputFormHint;
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

    public String getSuccessMessage() {
        return successMessage;
    }

    public String getFailureHint() {
        return failureHint;
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

    public String getInputFormHint() {
        return inputFormHint;
    }
}
