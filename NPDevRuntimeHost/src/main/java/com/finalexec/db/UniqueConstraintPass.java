package com.finalexec.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.npdev.kernel.storage.sql.SqlDialects;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/**
 * T2.B.4 (pure mechanical extraction): LNCH-1 Phase 5 (5.1)'s unique-constraint pass, split out of
 * {@link SchemaLifecycleExecutor} verbatim -- no behavior change. {@code applyUniqueConstraints} was
 * {@code private} and reachable only from {@code afterMigrate} (which stays on the executor -- it is
 * one of the class's two public entry points), so it moves here in full rather than leaving a
 * wrapper. Flat sibling in {@code com.finalexec.db}, not a subpackage -- see {@link TableRenamePass}'s
 * class javadoc for why.
 */
final class UniqueConstraintPass {

    private UniqueConstraintPass() {
    }

    /**
     * LNCH-1 P5 (5.1). Called by {@code SchemaLifecycleExecutor#afterMigrate} on every boot (cheap,
     * idempotent -- an empty {@code businessTableUniqueConstraints} manifest, the common case
     * pre-Phase-5, returns immediately). Runs strictly AFTER {@code flyway.migrate()} has already
     * applied the R__ additive-columns migration, so a unique constraint declared alongside a
     * brand-new nullable column always finds that column already present (new rows' NULLs never
     * collide under standard SQL unique-constraint semantics, so no dirty-data pre-check is even
     * needed for that case). For a constraint newly declared on an ALREADY-EXISTING column,
     * pre-checks live data for duplicate tuples (tenant-scoped or global, per
     * {@link SchemaLifecycleExecutor.UniqueConstraintDecl#tenantScoped} -- matching
     * {@code SchemaRealizationEmitter#appendBusinessTable}'s fresh-CREATE rule) before applying the
     * constraint.
     *
     * <p><b>All-or-nothing per boot, same shape as {@code applyRequiredFieldBackfills}:</b> pass 1
     * checks every declared constraint (read-only); if ANY table has violating data, throws before
     * applying ANY constraint this boot (so a clean table's constraint is not partially applied
     * while a dirty table's still needs attention -- consistent, easy-to-reason-about all-or-
     * nothing semantics). Pass 2 applies every clean, not-yet-applied constraint.
     *
     * <p>Idempotent by construction: {@link #constraintExists} re-checks
     * {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS} fresh on every call (not the same-boot pending
     * list only), so a crash between two constraint applications converges on the next boot without
     * re-attempting an already-applied constraint or erroring on a duplicate-name ADD CONSTRAINT.
     */
    static void applyUniqueConstraints(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        if (manifest.businessTableUniqueConstraints().isEmpty()) {
            return;
        }
        record PendingUniqueConstraint(String table, SchemaLifecycleExecutor.UniqueConstraintDecl decl) {
        }
        List<String> violationMessages = new ArrayList<>();
        List<PendingUniqueConstraint> toApply = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> entry
                    : manifest.businessTableUniqueConstraints().entrySet()) {
                String table = entry.getKey();
                Set<String> liveColumns = SchemaLifecycleExecutor.readActualColumns(metadata, table);
                if (liveColumns.isEmpty()) {
                    continue; // table not created yet this boot -- nothing to check/apply
                }
                for (SchemaLifecycleExecutor.UniqueConstraintDecl decl : entry.getValue()) {
                    if (!liveColumns.containsAll(decl.columns())) {
                        continue; // a declared column doesn't exist live yet -- nothing to apply this boot
                    }
                    if (constraintExists(connection, table, decl.name())) {
                        continue; // already applied -- idempotent no-op
                    }
                    List<String> duplicateKeys = findDuplicateKeys(connection, table, decl);
                    if (!duplicateKeys.isEmpty()) {
                        List<String> sample = duplicateKeys.size() > 20 ? duplicateKeys.subList(0, 20) : duplicateKeys;
                        violationMessages.add("table '" + table + "' unique constraint on ("
                                + String.join(", ", decl.columns()) + ")"
                                + (decl.tenantScoped() ? " [tenant-scoped]" : " [global]") + " has "
                                + duplicateKeys.size() + " violating tuple(s), e.g.: " + sample);
                        continue;
                    }
                    toApply.add(new PendingUniqueConstraint(table, decl));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed checking existing data against new unique constraint(s)", exception);
        }
        if (!violationMessages.isEmpty()) {
            // Fingerprint not yet written this boot (afterMigrate's own write happens after this
            // call returns) -- readStoredFingerprintPublic still reports the pre-this-attempt value,
            // matching every other refusal's "from_fingerprint" history-row convention.
            // R4 (F5): record the violation messages as items_json with a UNIQUE_PRECHECK label,
            // instead of an empty, classification-less REFUSED row.
            SchemaHistoryStore.insertRawHistoryRow(dataSource, SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource),
                    manifest.schemaFingerprint(), "UNIQUE_PRECHECK", violationMessages, "REFUSED");
            throw new IllegalStateException("Schema change adds new unique constraint(s), but existing data "
                    + "violates them (LNCH-1 Phase 5). Resolve the duplicate row(s) first, or relax the constraint: "
                    + violationMessages + " -- see docs/SCHEMA_EVOLUTION.md#tightened-uniqueness.");
        }
        if (toApply.isEmpty()) {
            return;
        }
        List<String> applied = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (PendingUniqueConstraint pending : toApply) {
                executeAddUniqueConstraint(connection, pending.table(), pending.decl());
                applied.add(pending.table() + "." + pending.decl().name());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying new unique constraint(s) (" + applied.size() + "/"
                    + toApply.size() + " applied before failure: " + applied + ")", exception);
        }
        System.out.println("NPDev schema lifecycle: applied new unique constraint(s): " + applied);
    }

    private static boolean constraintExists(Connection connection, String table, String constraintName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().constraintExistsSql())) {
            statement.setString(1, constraintName);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        return indexExists(connection, table, constraintName);
    }

    /**
     * Ordinary (non-anchor) unique fields are bootstrapped by {@code SchemaRealizationEmitter} as a
     * plain {@code CREATE UNIQUE INDEX IF NOT EXISTS ux_<table>_<column>} -- not an
     * {@code ADD CONSTRAINT} -- under the exact same {@code ux_...} name this class later tries to
     * {@code ADD CONSTRAINT} with (see {@link #executeAddUniqueConstraint}). {@code
     * INFORMATION_SCHEMA.TABLE_CONSTRAINTS} only lists true constraints, not plain indexes, so on
     * Postgres a same-named index from V1's bootstrap is invisible to the check above and {@code ADD
     * CONSTRAINT} then collides with the index's underlying relation --
     * {@code ERROR: relation "ux_..." already exists}, fatal on Postgres (H2 tolerates the duplicate
     * name and silently no-ops, which is why this was missed until the first real Postgres boot).
     * {@link DatabaseMetaData#getIndexInfo} is standard JDBC metadata and portable across engines, so
     * it closes the gap without engine-specific SQL.
     */
    private static boolean indexExists(Connection connection, String table, String indexName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getIndexInfo(null, null, candidate, false, false)) {
                while (resultSet.next()) {
                    String existingIndexName = resultSet.getString("INDEX_NAME");
                    if (existingIndexName != null && existingIndexName.equalsIgnoreCase(indexName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code GROUP BY ... HAVING COUNT(*) > 1}, tenant-scoped or global per
     * {@link SchemaLifecycleExecutor.UniqueConstraintDecl#tenantScoped}. Rows where any declared
     * unique column is {@code NULL} are excluded -- standard SQL unique-constraint semantics never
     * treat NULL-vs-NULL as a collision, so a naive {@code GROUP BY} (which DOES treat NULLs as
     * equal) would otherwise over-report.
     */
    private static List<String> findDuplicateKeys(Connection connection, String table,
            SchemaLifecycleExecutor.UniqueConstraintDecl decl) throws SQLException {
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        List<String> groupColumns = new ArrayList<>();
        if (decl.tenantScoped()) {
            groupColumns.add("tenant_id");
        }
        List<String> notNullColumns = new ArrayList<>();
        for (String column : decl.columns()) {
            String safeColumn = SchemaLifecycleExecutor.safeIdentifier(column);
            groupColumns.add(safeColumn);
            notNullColumns.add(safeColumn);
        }
        String columnList = String.join(", ", groupColumns);
        StringBuilder sql = new StringBuilder("SELECT ").append(columnList).append(", COUNT(*) FROM ").append(safeTable);
        if (!notNullColumns.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < notNullColumns.size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                sql.append(notNullColumns.get(i)).append(" IS NOT NULL");
            }
        }
        sql.append(" GROUP BY ").append(columnList).append(" HAVING COUNT(*) > 1");
        List<String> duplicates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString());
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= groupColumns.size(); i++) {
                    values.add(String.valueOf(resultSet.getObject(i)));
                }
                duplicates.add("(" + String.join(", ", values) + ")");
            }
        }
        return duplicates;
    }

    private static void executeAddUniqueConstraint(Connection connection, String table,
            SchemaLifecycleExecutor.UniqueConstraintDecl decl) throws SQLException {
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        String safeConstraint = SchemaLifecycleExecutor.safeIdentifier(decl.name());
        List<String> columns = new ArrayList<>();
        if (decl.tenantScoped()) {
            columns.add("tenant_id");
        }
        for (String column : decl.columns()) {
            columns.add(SchemaLifecycleExecutor.safeIdentifier(column));
        }
        String sql = "ALTER TABLE " + safeTable + " ADD CONSTRAINT " + safeConstraint
                + " UNIQUE (" + String.join(", ", columns) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
