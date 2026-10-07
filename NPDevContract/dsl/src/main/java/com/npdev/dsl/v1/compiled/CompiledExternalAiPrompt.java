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
        Integer maxOutputTokens
) {
}
