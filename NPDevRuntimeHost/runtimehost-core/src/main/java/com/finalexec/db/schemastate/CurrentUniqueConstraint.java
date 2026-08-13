package com.finalexec.db.schemastate;

import java.util.List;

/**
 * A unique constraint as it actually exists in the live database (schema-engine rebuild, Phase 1).
 *
 * @param name    lower-cased constraint name
 * @param columns the constrained columns, lower-cased, in constraint order
 */
public record CurrentUniqueConstraint(String name, List<String> columns) {
}
