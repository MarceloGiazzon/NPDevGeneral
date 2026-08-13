package com.npdev.dsl.v1.pack;

/**
 * PK-4 Stage C: one operation inside a {@code migrations["<oldVersion> -> <newVersion>"]} array --
 * the DSL (concept/field-name) level vocabulary a pack author hand-writes to describe what one
 * version hop actually did. Mirrors the generator's own {@code SchemaDeltaItem} naming convention.
 *
 * <p>Only {@link RenameField} and {@link RenameConcept} feed {@link PackMigrationComposer}'s
 * synthesis -- {@link AddField} and {@link DropField} carry no rename semantics and are never
 * consulted by it. They exist purely as an audit trail: the ordinary additive/destructive diff
 * already handles an add or a drop correctly from the pack's current-version shape alone, with no
 * chain replay needed. This is deliberate, not an omission -- it is what keeps these two op kinds
 * structurally incapable of causing data loss even if a pack author gets one wrong.
 */
public sealed interface PackMigrationOp {

    /** The {@code concept} this op names, or the concept a field-level op belongs to. */
    String concept();

    record RenameField(String concept, String from, String to) implements PackMigrationOp {
        public RenameField {
            requireNonBlank(concept, "concept");
            requireNonBlank(from, "from");
            requireNonBlank(to, "to");
        }
    }

    record RenameConcept(String from, String to) implements PackMigrationOp {
        public RenameConcept {
            requireNonBlank(from, "from");
            requireNonBlank(to, "to");
        }

        @Override
        public String concept() {
            return from;
        }
    }

    record AddField(String concept, String field) implements PackMigrationOp {
        public AddField {
            requireNonBlank(concept, "concept");
            requireNonBlank(field, "field");
        }
    }

    record DropField(String concept, String field) implements PackMigrationOp {
        public DropField {
            requireNonBlank(concept, "concept");
            requireNonBlank(field, "field");
        }
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
