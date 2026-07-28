package com.finalexec.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

/**
 * T2.B.4 (pure mechanical extraction): LNCH-1 T1 (finding T-B1)'s platform-column repair pass --
 * restoring {@code NOT NULL} on {@code version}/{@code row_version}/{@code tenant_id} for any table
 * an earlier build left relaxed -- split out of {@link SchemaLifecycleExecutor} verbatim, no behavior
 * change. {@link SchemaLifecycleExecutor#tightenPlatformColumns} (which stays on the executor as a
 * thin delegating wrapper -- its own javadoc documents it as directly unit-testable against a real H2
 * {@link DataSource}, following {@code relaxNoLongerRequiredColumns}'s precedent, even though no test
 * currently exercises it directly) calls straight into this sibling class. Flat sibling in
 * {@code com.finalexec.db}, not a subpackage -- see {@link TableRenamePass}'s class javadoc for why.
 */
final class PlatformColumnPass {

    private PlatformColumnPass() {
    }

    /** The platform default for a {@link SchemaLifecycleExecutor#REPAIRABLE_PLATFORM_COLUMNS} entry,
     * as a bound parameter value (never string-concatenated into DDL). */
    private static Object platformColumnDefault(String column) {
        return switch (column) {
            case "version", "row_version" -> 0L;
            case "tenant_id" -> "default";
            default -> throw new IllegalStateException("No platform default is defined for column: " + column);
        };
    }

    /**
     * LNCH-1 T1 (finding T-B1), Half B -- the repair half. Restores {@code NOT NULL} on the
     * platform-managed columns of any table where it is missing, backfilling existing NULLs to the
     * fixed platform default first.
     *
     * <p><b>Why this is needed at all:</b> Half A (the exclusion in
     * {@code relaxNoLongerRequiredColumns}) only stops the bleeding. Every app already upgraded by a
     * build carrying the old behaviour has permanently nullable {@code version}, {@code row_version}
     * and {@code tenant_id}, and nothing else would ever put them back.
     *
     * <p><b>Why it is safe to run unconditionally,</b> in the same place and for the same reason the
     * relax pass does (before {@code classify} ever sees the table): tightening a platform column
     * whose default is fixed and known can never lose data -- the only writes are "give the rows that
     * have no value the value they would have been created with" and "re-assert a constraint the
     * generator's own fresh CREATE TABLE always emits". Leaving it to a later phase would also mean
     * the very next boot re-relaxed it.
     *
     * <p><b>Idempotent by construction,</b> exactly like {@code addBackfillAndTightenColumn}: live
     * nullability is re-read via {@code isColumnNotNull} on every call, so an already-strict column
     * is a no-op and produces no history row (see {@code recordStepPass}'s empty-list contract --
     * no noise rows on converged boots).
     *
     * <p>A table whose platform column is <em>absent entirely</em> (a very old app) is not this
     * pass's concern -- the additive migration adds it. Only live, nullable columns are touched.
     */
    static void tightenPlatformColumns(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        record Tightening(String table, String column, Object platformDefault) {
        }
        List<Tightening> plan = new ArrayList<>();
        List<String> tightened = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : manifest.businessTableColumns().keySet()) {
                Set<String> actualColumns = SchemaLifecycleExecutor.readActualColumns(metadata, table);
                if (actualColumns.isEmpty()) {
                    continue; // brand-new table -- V1's CREATE TABLE IF NOT EXISTS emits it strict already
                }
                for (String column : SchemaLifecycleExecutor.REPAIRABLE_PLATFORM_COLUMNS) {
                    if (!actualColumns.contains(column)) {
                        continue; // absent entirely -- the additive migration's job, not this pass's
                    }
                    if (SchemaLifecycleExecutor.isColumnNotNull(connection, table, column)) {
                        continue; // already strict -- idempotent no-op
                    }
                    plan.add(new Tightening(table, column, platformColumnDefault(column)));
                }
            }
            // R4 (F5): one write-before-execute audit row for the whole repair pass -- the audit trail
            // must show that a repair happened, not merely that the columns are strict now.
            List<String> itemDetails = new ArrayList<>();
            for (Tightening item : plan) {
                itemDetails.add("TIGHTEN_PLATFORM_COLUMN " + item.table() + "." + item.column()
                        + " DEFAULT " + item.platformDefault());
            }
            SchemaHistoryStore.recordStepPass(dataSource, manifest, "TIGHTEN_PLATFORM_COLUMNS", itemDetails, () -> {
                for (Tightening item : plan) {
                    executeBackfillAndSetNotNull(connection, item.table(), item.column(), item.platformDefault());
                    tightened.add(item.table() + "." + item.column());
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed restoring NOT NULL on platform-managed column(s)", exception);
        }
        if (!tightened.isEmpty()) {
            System.out.println("NPDev schema lifecycle: restored NOT NULL on platform-managed column(s) "
                    + "relaxed by an earlier build (LNCH-1 T-B1 repair): " + tightened);
        }
    }

    /**
     * Bound-parameter {@code UPDATE ... WHERE c IS NULL} -&gt; {@code SET NOT NULL}, for
     * {@link #tightenPlatformColumns}. The same two trailing steps as {@code addBackfillAndTightenColumn}
     * (there is no {@code ADD COLUMN} step here: this pass only ever runs against a column already
     * proven live), and needs no engine dialect branch for the same reason that method documents --
     * {@code ALTER COLUMN ... SET NOT NULL} is identical syntax on H2 and Postgres.
     */
    private static void executeBackfillAndSetNotNull(Connection connection, String table, String column,
            Object platformDefault) throws SQLException {
        String safeTable = SchemaLifecycleExecutor.safeIdentifier(table);
        String safeColumn = SchemaLifecycleExecutor.safeIdentifier(column);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + safeTable + " SET " + safeColumn + " = ? WHERE " + safeColumn + " IS NULL")) {
            update.setObject(1, platformDefault);
            update.executeUpdate();
        }
        try (PreparedStatement notNull = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " ALTER COLUMN " + safeColumn + " SET NOT NULL")) {
            notNull.executeUpdate();
        }
    }
}
