package com.npdev.dsl.v1.compiled;

/** P4 (G3): a named {@code externalAi.prompts[]} entry, compiled from {@code ExternalAiPromptAst}. */
public record CompiledExternalAiPrompt(
        String name,
        String description,
        String vendor,
        String model,
        String template,
        String image,
        String outputSchemaJson,
        Integer maxOutputTokens,
        String review,
        String profile,
        String effort
) {
    /** AI7-1: {@code review} defaults to {@code none}. */
    public CompiledExternalAiPrompt {
        review = review == null || review.isBlank() ? "none" : review.trim();
    }

    public CompiledExternalAiPrompt(String name, String description, String vendor, String model, String template,
                                    String image, String outputSchemaJson, Integer maxOutputTokens, String review) {
        this(name, description, vendor, model, template, image, outputSchemaJson, maxOutputTokens, review, null, null);
    }

    public CompiledExternalAiPrompt(String name, String description, String vendor, String model, String template,
                                    String image, String outputSchemaJson, Integer maxOutputTokens) {
        this(name, description, vendor, model, template, image, outputSchemaJson, maxOutputTokens, null);
    }
}
