package com.finalexec.db.schemastate;

import java.util.List;
import java.util.Map;

/**
 * One table as it actually exists in the live database (schema-engine rebuild, Phase 1).
 *
 * @param name              lower-cased table name
 * @param columns           columns keyed by lower-cased column name
 * @param primaryKeyColumns the primary-key columns, lower-cased, in key order
 * @param uniques           unique constraints on this table
 * @param foreignKeys       foreign keys declared on this table
 * @param indexes           indexes on this table (may include PK/unique backing indexes)
 */
public record CurrentTable(
        String name,
        Map<String, CurrentColumn> columns,
        List<String> primaryKeyColumns,
        List<CurrentUniqueConstraint> uniques,
        List<CurrentForeignKey> foreignKeys,
        List<CurrentIndex> indexes
) {
}
