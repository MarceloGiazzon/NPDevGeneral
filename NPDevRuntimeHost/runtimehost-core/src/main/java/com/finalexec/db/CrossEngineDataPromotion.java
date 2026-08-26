package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.sql.Connection;
import com.npdev.kernel.storage.sql.PostgresDialect;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Move 9 A4 (docs/ACCEPTED_BOUNDARIES.md B10): operator-driven H2-&gt;Postgres data promotion.
 * Answers a settled product question (A4.0, recorded in ledger/items/REG-87.yml): prototype-on-H2
 * then promote-to-Postgres IS a chosen NPDev product arc, not just an adapter-pair artifact.
 *
 * <p>Deliberately narrow, per the boundary's own text ("data movement, not schema reconciliation"):
 * this class copies ROWS only. It never issues DDL and never realizes a schema -- the target table
 * must already exist (booted once, normally, pointed at the target database, which realizes an
 * empty-but-correct schema via the ALREADY engine-agnostic schema-realization path the
 * {@code engine-variant} corpus families prove). {@link #preview} is always read-only, writing
 * nothing to either side; {@link #apply} is the only method that writes, and only to the target.
 *
 * <p>Typed, not a generic {@code SELECT *}: every column is copied according to the SQL type the
 * SAME {@link SchemaLifecycleExecutor.SchemaManifest#businessTableColumnTypes()} map already carries
 * (the single existing source of truth for a column's declared type, shared with diffing/backfill --
 * not a new predicate dialect). A JSONB-typed column round-trips through the exact decode logic
 * {@link SchemaDropSnapshotWriter#decodeJsonColumnValue} already uses for the same ambiguity (H2
 * hands a JSON column back as a String or a byte[], sometimes double-quoted); every other type is a
 * plain {@code getObject}/{@code setObject} passthrough, which the H2 and Postgres JDBC drivers both
 * coerce correctly for the standard types NPDev declares (UUID, BIGINT, VARCHAR, BOOLEAN, DATE,
 * TIMESTAMP WITH TIME ZONE).
 */
public final class CrossEngineDataPromotion {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CrossEngineDataPromotion() {
    }

    /** One table's source/target row counts, known BEFORE any copy -- {@code targetRowCountBefore}
     * is normally 0 (a freshly realized, empty target table) but is reported honestly either way. */
    public record TableCounts(String table, long sourceRowCount, long targetRowCountBefore) {
    }

    /** A column whose type needs the operator's attention before promoting -- JSONB (re-encoded, not
     * a raw passthrough) and UUID (cross-driver coercion) are always surfaced; everything else is a
     * plain passthrough and not worth calling out. */
    public record TypeMappingNote(String table, String column, String sqlType, String note) {
    }

    public record Preview(List<TableCounts> tableCounts, List<TypeMappingNote> notes) {
    }

    /** {@code error} is null on a clean copy; {@code matched} is true only when the copy raised no
     * error AND the target's row count after equals the source's row count -- the DoD's own bar
     * ("refuse to report success on any mismatch"). */
    public record TableCopyResult(String table, long sourceRowCount, long rowsCopied, long targetRowCountAfter,
                            boolean matched, String error) {
    }

    public record PromotionResult(List<TableCopyResult> tables, boolean allMatched) {
    }

    /** Read-only: reports per-table source/target counts and the type-mapping notes an operator
     * should review before promoting. Opens connections to both sides but writes to neither. */
    public static Preview preview(DataSource source, DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest) {
        List<TableCounts> counts = new ArrayList<>();
        List<TypeMappingNote> notes = new ArrayList<>();
        for (String table : manifest.businessTables()) {
            long sourceCount = countIfExists(source, table);
            long targetCountBefore = countIfExists(target, table);
            counts.add(new TableCounts(table, sourceCount, targetCountBefore));
            for (Map.Entry<String, String> entry
                    : manifest.businessTableColumnTypes().getOrDefault(table, Map.of()).entrySet()) {
                String type = entry.getValue();
                if (isJsonType(type)) {
                    notes.add(new TypeMappingNote(table, entry.getKey(), type,
                            "structured JSON column -- copied via decode/re-encode as "
                            + PostgresDialect.INSTANCE.jsonColumnType()
                            + ", not a raw byte passthrough"));
                } else if ("UUID".equalsIgnoreCase(type)) {
                    notes.add(new TypeMappingNote(table, entry.getKey(), type,
                            "UUID column -- bound via setObject, relies on the target driver's own UUID coercion"));
                }
            }
        }
        return new Preview(counts, notes);
    }

    /**
     * Copies every row of every business table from {@code source} to {@code target}. Takes A1's
     * migration claim ({@link MigrationClaimStore}) on the target for the duration, so a concurrent
     * boot/migration against the same target cannot interleave with the copy. A table whose target
     * does not yet exist is reported as a per-table failure (never a schema-creating fallback); a
     * table whose copy raises mid-way is caught, reported with however many rows it got through, and
     * does NOT abort the remaining tables -- the caller always gets a complete, honest per-table
     * report, never a silent partial run.
     */
    public static PromotionResult apply(DataSource source, DataSource target, SchemaLifecycleExecutor.SchemaManifest manifest) {
        // NOTE: MigrationClaimStore.clear() is the SUPERUSER crashed-holder escape hatch -- it only
        // clears the human-readable row and, on Postgres, deliberately does NOT touch the live
        // pg_advisory_lock (see its own javadoc). The normal release path is release(dataSource,
        // instanceId) using the SAME instanceId claim() returned; using clear() here would leak the
        // held advisory-lock connection and wedge every subsequent promotion against this target.
        MigrationClaimStore.Claim claim = MigrationClaimStore.claim(target, false);
        try {
            List<TableCopyResult> results = new ArrayList<>();
            for (String table : manifest.businessTables()) {
                results.add(copyTable(source, target, table, manifest));
            }
            boolean allMatched = results.stream().allMatch(TableCopyResult::matched);
            return new PromotionResult(results, allMatched);
        } finally {
            if (claim != null) {
                MigrationClaimStore.release(target, claim.instanceId());
            }
        }
    }

    private static TableCopyResult copyTable(
            DataSource source, DataSource target, String table, SchemaLifecycleExecutor.SchemaManifest manifest
    ) {
        Map<String, String> declaredTypes = manifest.businessTableColumnTypes().getOrDefault(table, Map.of());
        try (Connection sourceConnection = source.getConnection(); Connection targetConnection = target.getConnection()) {
            long sourceCount = tableExistsLive(sourceConnection, table) ? countRows(sourceConnection, table) : 0L;
            if (!tableExistsLive(targetConnection, table)) {
                // QUAL-38 (item 4, SUPPORT_FEATURES_PLAN_2026-08-26): a missing target table is a
                // per-table SKIP, not a whole-promotion abort. This used to THROW BoundaryBootException,
                // which apply()'s loop never caught -- silently aborting every table after this one in
                // iteration order, contradicting this class's own javadoc ("does NOT abort the remaining
                // tables -- the caller always gets a complete, honest per-table report") and the
                // pre-existing CrossEngineDataPromotionTest#applyReportsFailureWhenTargetTableMissing,
                // which asserts exactly this TableCopyResult.error() shape. B10:data_only_promotion
                // (2026-08-25 W2.3, docs/ACCEPTED_BOUNDARIES.md) is still carried as the message prefix,
                // same convention B2/B4/B5/B9 use, even though this path no longer throws.
                return new TableCopyResult(table, sourceCount, 0L, 0L, false,
                        "B10:data_only_promotion:Cross-engine promotion refused for table '" + table
                                + "': target table does not exist. Realize the schema on the target first "
                                + "(boot the app pointed at the target database), then promote data. Schema "
                                + "reconciliation is not supported. Run `npdev why B10` for the full explanation.");
            }
            Set<String> sourceColumns = SchemaLifecycleExecutor.readActualColumns(sourceConnection.getMetaData(), table);
            Set<String> targetColumns = SchemaLifecycleExecutor.readActualColumns(targetConnection.getMetaData(), table);
            List<String> columns = declaredTypes.keySet().stream()
                    .filter(column -> containsIgnoreCase(sourceColumns, column) && containsIgnoreCase(targetColumns, column))
                    .toList();

            String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
            String columnList = columns.stream().map(SchemaLifecycleExecutor::safeIdentifier).collect(Collectors.joining(", "));
            String placeholders = columns.stream().map(ignored -> "?").collect(Collectors.joining(", "));
            String selectSql = "SELECT " + columnList + " FROM " + safeTable;
            String insertSql = "INSERT INTO " + safeTable + " (" + columnList + ") VALUES (" + placeholders + ")";

            long copied = 0;
            String error = null;
            try (PreparedStatement selectStatement = sourceConnection.prepareStatement(selectSql);
                    ResultSet resultSet = selectStatement.executeQuery();
                    PreparedStatement insertStatement = targetConnection.prepareStatement(insertSql)) {
                while (resultSet.next()) {
                    for (int index = 0; index < columns.size(); index++) {
                        bindValue(insertStatement, index + 1, resultSet, index + 1, declaredTypes.get(columns.get(index)));
                    }
                    insertStatement.executeUpdate();
                    copied++;
                }
            } catch (SQLException exception) {
                error = exception.getMessage();
            }
            long targetCountAfter = countRows(targetConnection, table);
            boolean matched = error == null && targetCountAfter == sourceCount;
            return new TableCopyResult(table, sourceCount, copied, targetCountAfter, matched, error);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed copying table " + table, exception);
        }
    }

    private static void bindValue(
            PreparedStatement insertStatement, int insertIndex, ResultSet resultSet, int selectIndex, String sqlType
    ) throws SQLException {
        if (isJsonType(sqlType)) {
            Object decoded = SchemaDropSnapshotWriter.decodeJsonColumnValue(resultSet.getObject(selectIndex));
            if (decoded == null) {
                insertStatement.setNull(insertIndex, Types.OTHER);
                return;
            }
            if (isPostgres(insertStatement.getConnection())) {
                try {
                    PGobject jsonValue = new PGobject();
                    jsonValue.setType(PostgresDialect.INSTANCE.jsonColumnType());
                    jsonValue.setValue(OBJECT_MAPPER.writeValueAsString(decoded));
                    insertStatement.setObject(insertIndex, jsonValue);
                } catch (Exception exception) {
                    throw new SQLException("Failed encoding JSON column value for insert", exception);
                }
            } else {
                try {
                    insertStatement.setObject(insertIndex, OBJECT_MAPPER.writeValueAsString(decoded));
                } catch (Exception exception) {
                    throw new SQLException("Failed encoding JSON column value for insert", exception);
                }
            }
            return;
        }
        insertStatement.setObject(insertIndex, resultSet.getObject(selectIndex));
    }

    private static boolean isJsonType(String sqlType) {
        // Promotion reads a manifest written by whichever engine was the SOURCE, so this asks the
        // Postgres dialect specifically -- its JSON name set is the superset (json + jsonb) and is
        // what the manifest can contain. Not SqlDialects.active(): the app's own engine is not
        // necessarily either side of a cross-engine copy.
        return PostgresDialect.INSTANCE.isJsonColumnType(sqlType);
    }

    private static boolean isPostgres(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
    }

    private static boolean containsIgnoreCase(Set<String> columns, String column) {
        for (String candidate : columns) {
            if (candidate.equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tableExistsLive(Connection connection, String table) throws SQLException {
        return !SchemaLifecycleExecutor.readActualColumns(connection.getMetaData(), table).isEmpty();
    }

    private static long countIfExists(DataSource dataSource, String table) {
        try (Connection connection = dataSource.getConnection()) {
            return tableExistsLive(connection, table) ? countRows(connection, table) : 0L;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed counting rows in table " + table, exception);
        }
    }

    private static long countRows(Connection connection, String table) throws SQLException {
        String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + safeTable)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }
}
