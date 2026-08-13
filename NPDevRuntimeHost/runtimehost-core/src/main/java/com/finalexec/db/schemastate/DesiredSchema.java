package com.finalexec.db.schemastate;

import java.util.Map;

/**
 * The schema the model intends (schema-engine rebuild, Phase 2) — the "desired" half of the canonical
 * desired-vs-current model (REG-6). Built by a pure function ({@code DesiredSchemaFactory}) from the
 * {@code SchemaManifest} + {@code ColumnFacts} (the {@code SchemaLifecycleExecutor} class-header
 * directive requires new code to consume {@code ColumnFacts}, never re-derive column semantics).
 * Diffed against a {@link CurrentSchema} by {@code SchemaDiffEngine}.
 *
 * @param tables tables keyed by lower-cased table name
 */
public record DesiredSchema(Map<String, DesiredTable> tables) {
}
