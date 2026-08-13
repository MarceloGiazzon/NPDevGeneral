package com.finalexec.db.schemastate;

import java.util.List;

/**
 * A foreign key the MODEL declares (schema-engine rebuild, SER-G8) — derived from a bond at generation
 * time and carried in the schema-realization manifest.
 *
 * <p>Deliberately <b>name-less</b>: constraint names are engine-generated ({@code widgets_pkey} on
 * Postgres, {@code CONSTRAINT_8} on H2), so the diff matches by column set + referenced table. Comparing
 * names would report a difference on every engine for the same logical constraint.
 */
public record DesiredForeignKey(List<String> columns, String referencedTable, List<String> referencedColumns) {
}
