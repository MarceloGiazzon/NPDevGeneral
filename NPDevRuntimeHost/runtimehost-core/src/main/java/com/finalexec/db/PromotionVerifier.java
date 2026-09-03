package com.finalexec.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

/**
 * 3.3 (B10 one-command H2-&gt;Postgres promotion, package 3.3): verification deeper than
 * {@link CrossEngineDataPromotion}'s own row-count check -- the plan's own words, "never 'Postgres
 * started successfully'". Three independent signals per table, all computed from a SINGLE scan of
 * each side (never a second round-trip):
 *
 * <ul>
 *   <li><b>Row count</b> -- already in {@link CrossEngineDataPromotion.TableCopyResult}, repeated
 *       here so a verify-only run (no apply in this process) still has it.</li>
 *   <li><b>Per-column null count</b> -- a copy that silently drops a value into NULL (a JSONB
 *       decode/encode failure that got swallowed, a type-coercion surprise) can match on row count
 *       alone; this catches it column by column.</li>
 *   <li><b>Order-independent content hash</b> -- a per-row hash, SUMMED (not chained) across rows so
 *       the result does not depend on read order, which two different engines' table scans are not
 *       guaranteed to agree on. Computed entirely in JAVA from {@code ResultSet#getObject}, never a
 *       database-side hash function -- keeps this class free of dialect-bound SQL (STOR-1) and
 *       avoids trusting two different engines' SQL hash functions to agree with each other at all.</li>
 * </ul>
 *
 * <p><b>Shape/constraint parity is not reimplemented here.</b> {@link ExternalSchemaVerification
 * #findExternalSchemaIncompatibilities} already answers "does this live schema satisfy the
 * manifest" -- column existence/type, nullability, unique constraints, AND foreign-key/index parity
 * (missing-only, matched by column set, exactly what the plan's done-when #2 asks for) -- built for
 * REG-7.1's {@code ExternallyManaged} boot check. Running it against the TARGET after promotion asks
 * the identical question this class needs answered, so it is reused verbatim rather than given a
 * second, drifting copy of the same comparison (REG-6's own argument, one file over).
 *
 * <p><b>Cross-engine value normalization is deliberately narrow, not exhaustive.</b> NPDev declares a
 * small, known column-type set (UUID, BIGINT, VARCHAR, BOOLEAN, DATE, TIMESTAMP WITH TIME ZONE, JSON/
 * JSONB -- {@link CrossEngineDataPromotion}'s own class javadoc enumerates it) and normalizes exactly
 * those: a JSON column is decoded (the same {@link SchemaDropSnapshotWriter#decodeJsonColumnValue}
 * {@link CrossEngineDataPromotion#bindValue} already uses) before hashing, so it compares by VALUE,
 * not by which engine's raw JSON/JSONB wire representation produced it; everything else falls back to
 * {@code String.valueOf}. This is not a claim that every conceivable JDBC type round-trips its
 * {@code toString()} identically across every engine (a numeric column's driver-chosen Java type, or a
 * timestamp's zone rendering, could in principle differ) -- only that it does for the declared set
 * this platform actually generates columns as.
 */
public final class PromotionVerifier {

    private PromotionVerifier() {
    }

    /** One column's null count on each side. {@link #matches} is the per-column verdict. */
    public record ColumnNullCount(String column, long sourceNulls, long targetNulls) {
        public boolean matches() {
            return sourceNulls == targetNulls;
        }
    }

    /**
     * One table's full verification. {@code error} is non-null only when a side could not be
     * scanned at all (connection/SQL failure) -- distinct from a scanned-but-mismatched table, which
     * reports through the other fields instead of hiding behind a generic error.
     */
    public record TableVerification(
            String table,
            long sourceRowCount, long targetRowCount,
            long sourceContentHash, long targetContentHash,
            List<ColumnNullCount> columnNullCounts,
            List<String> shapeProblems,
            String error) {

        public boolean rowCountMatches() {
            return error == null && sourceRowCount == targetRowCount;
        }

        public boolean contentHashMatches() {
            return error == null && sourceContentHash == targetContentHash;
        }

        public boolean nullCountsMatch() {
            return error == null && columnNullCounts.stream().allMatch(ColumnNullCount::matches);
        }

        /** The single verdict a caller should act on: every independent signal agreed. */
        public boolean verified() {
            return error == null && rowCountMatches() && contentHashMatches() && nullCountsMatch()
                    && shapeProblems.isEmpty();
        }
    }

    public record VerificationResult(List<TableVerification> tables, boolean allVerified) {
    }

    /**
     * Verifies every business table the manifest declares. Read-only on both sides -- never issues
     * DDL or DML, so it is safe to run standalone (not only right after an {@code apply}) to
     * re-confirm a promotion that already ran.
     */
    public static VerificationResult verify(
            DataSource source, DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest) {
        // One shape/constraint pass against the whole target, not per-table -- ExternalSchemaVerification
        // already walks every manifest-declared table itself; slicing its output by table below avoids
        // asking it once per table (N live-schema introspections) for what one call already answers.
        List<String> allShapeProblems =
                ExternalSchemaVerification.findExternalSchemaIncompatibilities(target, manifest);

        List<TableVerification> results = new ArrayList<>();
        for (String table : manifest.businessTables()) {
            results.add(verifyTable(source, target, table, manifest, allShapeProblems));
        }
        boolean allVerified = results.stream().allMatch(TableVerification::verified);
        return new VerificationResult(results, allVerified);
    }

    private static TableVerification verifyTable(
            DataSource source, DataSource target, String table,
            SchemaLifecycleExecutor.SchemaManifest manifest, List<String> allShapeProblems) {
        // findExternalSchemaIncompatibilities' own item shapes: "table (table missing)",
        // "table.column (...)", "table (missing unique constraint...)" -- every one either equals the
        // table name up to " (" or starts with "table." or "table (".
        List<String> tableShapeProblems = allShapeProblems.stream()
                .filter(problem -> problem.startsWith(table + " (") || problem.startsWith(table + "."))
                .toList();
        try {
            RowScan sourceScan = scanTable(source, table, manifest);
            RowScan targetScan = scanTable(target, table, manifest);
            List<ColumnNullCount> nullCounts = new ArrayList<>();
            for (String column : manifest.businessTableColumns().getOrDefault(table, List.of())) {
                nullCounts.add(new ColumnNullCount(column,
                        sourceScan.nullCountsByColumn().getOrDefault(column, 0L),
                        targetScan.nullCountsByColumn().getOrDefault(column, 0L)));
            }
            return new TableVerification(table, sourceScan.rowCount(), targetScan.rowCount(),
                    sourceScan.contentHash(), targetScan.contentHash(), nullCounts, tableShapeProblems, null);
        } catch (SQLException failure) {
            return new TableVerification(table, -1L, -1L, 0L, 0L, List.of(), tableShapeProblems,
                    "Failed scanning table '" + table + "' for verification: " + failure.getMessage());
        }
    }

    private record RowScan(long rowCount, long contentHash, Map<String, Long> nullCountsByColumn) {
    }

    /** One pass: counts rows, counts nulls per column, and accumulates the order-independent content
     *  hash -- all from the same {@code ResultSet}, so a table with a million rows is read once, not
     *  three times. A table that does not exist on this side scans as empty, not an error -- matches
     *  {@link CrossEngineDataPromotion}'s own tolerant treatment of a not-yet-realized table. */
    private static RowScan scanTable(
            DataSource dataSource, String table, SchemaLifecycleExecutor.SchemaManifest manifest)
            throws SQLException {
        List<String> columns = manifest.businessTableColumns().getOrDefault(table, List.of());
        Map<String, String> declaredTypes = manifest.businessTableColumnTypes().getOrDefault(table, Map.of());
        Map<String, Long> nullCounts = new LinkedHashMap<>();
        for (String column : columns) {
            nullCounts.put(column, 0L);
        }
        if (columns.isEmpty()) {
            return new RowScan(0L, 0L, nullCounts);
        }
        try (Connection connection = dataSource.getConnection()) {
            if (SchemaLifecycleExecutor.readActualColumns(connection.getMetaData(), table).isEmpty()) {
                return new RowScan(0L, 0L, nullCounts);
            }
            String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
            String columnList = columns.stream()
                    .map(SchemaLifecycleExecutor::safeIdentifier).collect(Collectors.joining(", "));
            long rowCount = 0L;
            long contentHash = 0L;
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT " + columnList + " FROM " + safeTable)) {
                while (resultSet.next()) {
                    rowCount++;
                    long rowHash = 17L;
                    for (int index = 0; index < columns.size(); index++) {
                        String column = columns.get(index);
                        Object value = resultSet.getObject(index + 1);
                        if (value == null) {
                            nullCounts.merge(column, 1L, Long::sum);
                            rowHash = rowHash * 31L;
                            continue;
                        }
                        rowHash = rowHash * 31L + normalizedFor(value, declaredTypes.get(column)).hashCode();
                    }
                    // SUMMED, not chained: makes the table's hash independent of scan order, which two
                    // different engines are not guaranteed to agree on for a plain unordered SELECT.
                    contentHash += rowHash;
                }
            }
            return new RowScan(rowCount, contentHash, nullCounts);
        }
    }

    /** See the class javadoc's "Cross-engine value normalization" note for scope. */
    private static String normalizedFor(Object value, String declaredSqlType) {
        if (declaredSqlType != null && isJsonType(declaredSqlType)) {
            Object decoded = SchemaDropSnapshotWriter.decodeJsonColumnValue(value);
            return String.valueOf(decoded);
        }
        return String.valueOf(value);
    }

    private static boolean isJsonType(String sqlType) {
        // Same reasoning as CrossEngineDataPromotion.isJsonType: ask the Postgres dialect specifically
        // (its JSON name set is the superset), never SqlDialects.active() -- this class runs against
        // BOTH sides of a cross-engine copy, neither of which is necessarily the app's own engine.
        return com.npdev.kernel.storage.sql.PostgresDialect.INSTANCE.isJsonColumnType(sqlType);
    }
}
