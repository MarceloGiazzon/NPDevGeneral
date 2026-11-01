package com.npdev.dsl.v1.compiled;

import java.util.Optional;

/**
 * REG-318(6): which concept and which field of it name a {@code reference} field's value for a person.
 * The same answer {@code CompiledMetadataCanonicalJson} publishes as the reference's {@code displayField}
 * (the declared {@code referenceSemantics.displayField}, else the target's inferred one), so a grouped
 * query labels a group exactly as the grid labels the reference.
 */
public final class CompiledReferenceDisplay {

    private CompiledReferenceDisplay() {
    }

    /** The target concept of a reference field and the field of it to show. */
    public record Target(String conceptName, String displayField) {
    }

    /** @return empty when {@code fieldName} is not a reference field of {@code conceptName} or its target is unknown. */
    public static Optional<Target> of(CompiledModel model, String conceptName, String fieldName) {
        if (model == null || conceptName == null || fieldName == null) {
            return Optional.empty();
        }
        CompiledConcept concept = model.findConcept(conceptName).orElse(null);
        if (concept == null) {
            return Optional.empty();
        }
        CompiledField field = concept.getFields().stream()
                .filter(candidate -> fieldName.equals(candidate.getName()))
                .findFirst()
                .orElse(null);
        if (field == null || field.getReferenceTarget() == null || field.getReferenceTarget().isBlank()) {
            return Optional.empty();
        }
        CompiledConcept target = model.findConcept(field.getReferenceTarget()).orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        CompiledReferenceSemantics semantics = field.getReferenceSemantics();
        String declared = semantics == null ? null : semantics.getDisplayField();
        String display = declared != null && !declared.isBlank()
                ? declared
                : CompiledMetadataCanonicalJson.inferReferenceDisplayField(target);
        return display == null || display.isBlank()
                ? Optional.empty()
                : Optional.of(new Target(target.getName(), display));
    }
}
