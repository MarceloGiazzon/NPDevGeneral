package com.npdev.dsl.v1.pack;

/**
 * One classified difference between an old and a new {@code pack.json} document.
 *
 * @param section  the top-level pack.schema.json array this finding belongs to (e.g.
 *                 {@code "concepts"}, {@code "panels"}, {@code "queries"}), or {@code "pack"} for a
 *                 top-level scalar/metadata change.
 * @param path     a dotted locator into the pack document, e.g. {@code "concepts.User.fields.email"}
 *                 or {@code "panels.OrderPanel"}. Never {@code null}.
 * @param classification one of {@link PackChangeClassification#PATCH},
 *                 {@link PackChangeClassification#ADDITIVE}, {@link PackChangeClassification#BREAKING}.
 * @param message  a human-readable, self-contained description of exactly what changed -- this is
 *                 what {@code PackPublishGate} echoes back in a refusal, so it must name the thing
 *                 that changed, not just say "changed".
 */
public record PackDiffFinding(
        String section,
        String path,
        PackChangeClassification classification,
        String message
) {
    public PackDiffFinding {
        if (section == null || section.isBlank()) {
            throw new IllegalArgumentException("section must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (classification == null) {
            throw new IllegalArgumentException("classification must not be null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
