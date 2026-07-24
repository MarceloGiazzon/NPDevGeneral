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
 */
public record DesiredTable(
        String name,
        Map<String, DesiredColumn> columns,
        List<DesiredUniqueConstraint> uniques,
        String renamedFromTable
) {
}
