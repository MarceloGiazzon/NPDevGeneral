package com.finalexec.db.schemastate;

import java.util.List;

/**
 * A foreign-key constraint as it actually exists in the live database (schema-engine rebuild, Phase 1),
 * read via {@code DatabaseMetaData.getImportedKeys}. NB (P0.2 asymmetry finding): the desired-side
 * {@code SchemaManifest} carries no explicit FK list — FKs are derived from bonds at generation. Phase 2
 * must synthesize the desired-side FK expectations from the same bond source, or the diff will report
 * every live FK as an unexplained extra.
 *
 * @param name              lower-cased constraint name
 * @param columns           the FK columns on this table, lower-cased, in key order
 * @param referencedTable   lower-cased referenced table name
 * @param referencedColumns the referenced columns, lower-cased, in key order
 * @param onDelete          the ON DELETE rule as reported by JDBC ({@code CASCADE}/{@code SET NULL}/
 *                          {@code RESTRICT}/{@code NO ACTION}), upper-cased, or {@code null} if unknown
 */
public record CurrentForeignKey(
        String name,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns,
        String onDelete
) {
}
