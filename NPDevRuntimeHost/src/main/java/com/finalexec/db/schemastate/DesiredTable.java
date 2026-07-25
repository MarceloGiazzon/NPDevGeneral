package com.finalexec.db.schemastate;

import java.util.List;
import java.util.Map;

/**
 * One table as the model intends it (schema-engine rebuild, Phase 2) — the "desired" mirror of
 * {@link CurrentTable}.
 *
 * @param name             lower-cased table name
 * @param columns          columns keyed by lower-cased column name
 * @param uniques          unique constraints the model declares
 * @param renamedFromTable the prior table name when the model declares a table rename, else {@code null}
 * @param foreignKeys      SER-G8: foreign keys the model declares (derived from bonds). Empty when the
 *                         manifest predates G8 or the table has no bonds.
 * @param indexes          SER-G8: indexes the model declares (unique invariants + bond lookup indexes).
 */
public record DesiredTable(
        String name,
        Map<String, DesiredColumn> columns,
        List<DesiredUniqueConstraint> uniques,
        String renamedFromTable,
        List<DesiredForeignKey> foreignKeys,
        List<DesiredIndex> indexes
) {
    /** SER-G8 backward-compatible convenience constructor matching the pre-G8 4-arg shape — defaults the
     *  FK/index lists to empty, so every existing construction (notably the pure-diff test fixtures) keeps
     *  compiling and behaving identically. */
    public DesiredTable(String name, Map<String, DesiredColumn> columns,
            List<DesiredUniqueConstraint> uniques, String renamedFromTable) {
        this(name, columns, uniques, renamedFromTable, List.of(), List.of());
    }
}
