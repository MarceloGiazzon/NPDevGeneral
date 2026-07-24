package com.finalexec.db.schemastate;

import java.util.List;

/**
 * An index as it actually exists in the live database (schema-engine rebuild, Phase 1), read via
 * {@code DatabaseMetaData.getIndexInfo}. The primary-key backing index and unique-constraint backing
 * indexes are reported here too by JDBC; Phase 2's diff must not double-count them against the PK /
 * unique-constraint facts (P0.2 asymmetry finding).
 *
 * @param name    lower-cased index name
 * @param columns the indexed columns, lower-cased, in ordinal position
 * @param unique  whether the index enforces uniqueness
 */
public record CurrentIndex(String name, List<String> columns, boolean unique) {
}
