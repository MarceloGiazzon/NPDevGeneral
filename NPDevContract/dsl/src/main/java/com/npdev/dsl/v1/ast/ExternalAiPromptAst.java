package com.npdev.dsl.v1.ast;

/**
 * P4 (G3, AI as a model capability): one named prompt a flow can run with
 * {@code capabilityCall externalAi.generate args: ["<name>", "$input"]}. {@code template} holds
 * {@code {{field}}} placeholders filled from the second arg's map (a dotted path reaches nested
 * values; a list/map is inserted as JSON). {@code image} optionally names the input field holding a
 * file reference sent alongside the text for vision. The answer must validate against
 * {@code outputSchemaJson} (a JSON Schema, kept as canonical JSON text) or the step fails -- a flow
 * never sees unvalidated vendor output. {@code vendor} must be one of {@code externalAi.vendors};
 * {@code model} overrides the vendor profile's default model when set.
 */
public record ExternalAiPromptAst(
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
