package com.npdev.generator.schemaevolution;

import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;

import java.util.List;

/**
 * LNCH-1 Phase 6 (task 6.1). A single itemized change in a {@link MigrationPlan}'s preview --
 * safe and destructive kinds alike (unlike {@link SchemaDeltaItem}, whose vocabulary is
 * destructive-only). See {@code NPDevContract/schemas/migration-plan.schema.json} for the
 * authoritative field/kind documentation this record mirrors exactly.
 *
 * <p>Kinds {@link Kind#DROP_COLUMN}, {@link Kind#DROP_TABLE}, {@link Kind#NARROW_TYPE}, and
 * {@link Kind#UNKNOWN} are {@link #destructive()} and carry a non-null {@link #stableString()} --
 * produced by constructing the corresponding {@link SchemaDeltaItem} record and calling its
 * {@code stableString()} directly (never re-derived by hand), so the string is byte-identical to
 * what {@code com.finalexec.db.SchemaDeltaReport} would independently compute for the same
 * underlying change. Every other kind is a safe, informational preview item with a null
 * {@code stableString} -- it never contributes to {@link MigrationPlan#destructiveAckToken()}.
 */
public record PlanItem(
        Kind kind,
        String table,
        String column,
        String fromType,
        String toType,
        String renamedFrom,
        List<String> constraintColumns,
        boolean destructive,
        String sqlPreview,
        String description,
        String stableString
) {

    public enum Kind {
        ADD_TABLE,
        ADD_COLUMN,
        ADD_COLUMN_BACKFILL,
        RENAME_TABLE,
        RENAME_COLUMN,
        WIDEN_TYPE,
        ADD_UNIQUE_CONSTRAINT,
        DROP_COLUMN,
        DROP_TABLE,
        NARROW_TYPE,
        UNKNOWN
    }

    static PlanItem addTable(String table) {
        return new PlanItem(Kind.ADD_TABLE, table, null, null, null, null, null, false,
                "CREATE TABLE " + table + " (...)",
                "New concept adds table '" + table + "'.", null);
    }

    static PlanItem renameTable(String table, String oldTable) {
        return new PlanItem(Kind.RENAME_TABLE, table, null, null, null, oldTable, null, false,
                "ALTER TABLE " + oldTable + " RENAME TO " + table,
                "Concept renamed: table '" + oldTable + "' becomes '" + table + "' (data preserved in place).", null);
    }

    static PlanItem addColumn(String table, String column, String toType) {
        return new PlanItem(Kind.ADD_COLUMN, table, column, null, toType, null, null, false,
                "ALTER TABLE " + table + " ADD COLUMN " + column + " " + toType,
                "New optional field adds column '" + table + "." + column + "'.", null);
    }

    static PlanItem addColumnBackfill(String table, String column, String toType, String literalDefaultJson) {
        return new PlanItem(Kind.ADD_COLUMN_BACKFILL, table, column, null, toType, null, null, false,
                "ALTER TABLE " + table + " ADD COLUMN " + column + " " + toType + "; "
                        + "UPDATE " + table + " SET " + column + " = " + literalDefaultJson + " WHERE " + column + " IS NULL; "
                        + "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NOT NULL",
                "New required field '" + table + "." + column + "' declares a literal default (" + literalDefaultJson
                        + "); existing rows are backfilled automatically at boot (LNCH-1 Phase 5), then the column is "
                        + "tightened to NOT NULL. Safe, not destructive.", null);
    }

    static PlanItem renameColumn(String table, String column, String oldColumn) {
        return new PlanItem(Kind.RENAME_COLUMN, table, column, null, null, oldColumn, null, false,
                "ALTER TABLE " + table + " RENAME COLUMN " + oldColumn + " TO " + column,
                "Field renamed: '" + table + "." + oldColumn + "' becomes '" + column + "' (data preserved in place).", null);
    }

    static PlanItem widenType(String table, String column, String fromType, String toType) {
        return new PlanItem(Kind.WIDEN_TYPE, table, column, fromType, toType, null, null, false,
                "ALTER TABLE " + table + " ALTER COLUMN " + column + " TYPE " + toType,
                "Type widened on '" + table + "." + column + "': " + fromType + " -> " + toType
                        + " (safe, applied in place, LNCH-1 Phase 3).", null);
    }

    static PlanItem addUniqueConstraint(String table, List<String> columns) {
        return new PlanItem(Kind.ADD_UNIQUE_CONSTRAINT, table, null, null, null, null, List.copyOf(columns), false,
                "ALTER TABLE " + table + " ADD CONSTRAINT ... UNIQUE (" + String.join(", ", columns) + ")",
                "New unique constraint on '" + table + " (" + String.join(", ", columns) + ")'. Existing data is "
                        + "pre-checked for violations at boot before this is applied (LNCH-1 Phase 5); not destructive, "
                        + "but a data pre-check is required.", null);
    }

    static PlanItem dropColumn(SchemaDeltaItem.DropColumn item) {
        return new PlanItem(Kind.DROP_COLUMN, item.table(), item.column(), item.sqlType(), null, null, null, true,
                "ALTER TABLE " + item.table() + " DROP COLUMN " + item.column(),
                "Field removed: column '" + item.table() + "." + item.column() + "' will be dropped, deleting its data. "
                        + "Requires acknowledgment (LNCH-1 Phase 4).", item.stableString());
    }

    static PlanItem dropTable(SchemaDeltaItem.DropTable item) {
        return new PlanItem(Kind.DROP_TABLE, item.table(), null, null, null, null, null, true,
                "DROP TABLE " + item.table() + " CASCADE",
                "Concept removed: table '" + item.table() + "' will be dropped, deleting all its data. Requires "
                        + "acknowledgment (LNCH-1 Phase 4). Row count is not previewable at generation time (no live "
                        + "database access here) -- the executor's own acknowledgment token at boot uses the ACTUAL "
                        + "live row count, so this preview's token may not byte-match a later boot if row count "
                        + "differs from this preview's placeholder; the executor remains the final authority.",
                item.stableString());
    }

    static PlanItem narrowType(SchemaDeltaItem.NarrowType item) {
        return new PlanItem(Kind.NARROW_TYPE, item.table(), item.column(), item.fromType(), item.toType(), null, null, true,
                "ALTER TABLE " + item.table() + " DROP COLUMN " + item.column() + "; ALTER TABLE " + item.table()
                        + " ADD COLUMN " + item.column() + " " + item.toType() + " -- data in this column will be LOST",
                "Type changed on '" + item.table() + "." + item.column() + "' in a way that is not a safe widening: "
                        + item.fromType() + " -> " + item.toType() + ". Executed as drop-and-recreate-column (data in "
                        + "this column will be lost). Requires acknowledgment (LNCH-1 Phase 4).", item.stableString());
    }

    static PlanItem unknown(SchemaDeltaItem.Unknown item) {
        return new PlanItem(Kind.UNKNOWN, item.table(), null, null, null, null, null, true,
                "-- cannot preview: " + item.description(),
                item.description(), item.stableString());
    }
}
