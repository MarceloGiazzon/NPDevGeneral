package com.finalexec.db.schemastate;

import java.util.Map;

/**
 * The live database's actual shape, read once per boot by {@code CurrentSchemaReader} (schema-engine
 * rebuild, Phase 1). This is the "current" half of the canonical desired-vs-current model that replaces
 * reconciliation-by-inference (REG-6): today ~12 ad-hoc {@code DatabaseMetaData} reads are scattered
 * across {@code SchemaLifecycleExecutor}'s passes, each re-deriving fragments of this. Phase 1 builds
 * and unit-proves this reader but wires it NOWHERE into the boot path — it must stay behavior-preserving
 * until Phase 4.
 *
 * @param tables tables keyed by lower-cased table name
 */
public record CurrentSchema(Map<String, CurrentTable> tables) {
}
