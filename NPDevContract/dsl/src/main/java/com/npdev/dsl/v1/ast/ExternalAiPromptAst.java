package com.npdev.dsl.v1.ast;

/**
 * P4 (G3, AI as a model capability): one named prompt a flow can run with
 * {@code capabilityCall externalAi.generate args: ["<name>", "$input"]}. {@code template} holds
 * {@code {{field}}} placeholders filled from the second arg's map (a dotted path reaches nested
 * values; a list/map is inserted as JSON). {@code image} optionally names the input field holding a
 * file reference sent alongside the text for vision. The answer must validate against
 * {@code outputSchemaJson} (a JSON Schema, kept as canonical JSON text) or the step fails -- a flow
 * never sees unvalidated vendor output. {@code vendor} must be one of {@code externalAi.vendors};
 * {@code model} overrides the vendor profile's default model when set. AI7-1: {@code review}
 * (required|optional|none, default none) says whether a human must approve the answer before a
 * flow applies it. AIP-3 (3.4a): {@code profile} names an AI catalogue profile instead of
 * {@code vendor}+{@code model} (the compiler resolves it, see {@code AiCatalogueProfiles}); {@code effort}
 * (low|medium|high) overrides the profile's effort.
 */
public record ExternalAiPromptAst(
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
    /** AI7-1: {@code review} defaults to {@code none} -- the prompt's answer reaches the flow unreviewed. */
    public ExternalAiPromptAst {
        review = review == null || review.isBlank() ? "none" : review.trim();
    }

    public ExternalAiPromptAst(String name, String description, String vendor, String model, String template,
                               String image, String outputSchemaJson, Integer maxOutputTokens, String review) {
        this(name, description, vendor, model, template, image, outputSchemaJson, maxOutputTokens, review, null, null);
    }

    public ExternalAiPromptAst(String name, String description, String vendor, String model, String template,
                               String image, String outputSchemaJson, Integer maxOutputTokens) {
        this(name, description, vendor, model, template, image, outputSchemaJson, maxOutputTokens, null);
    }

    public boolean reviewRequired() {
        return "required".equals(review);
    }
}
