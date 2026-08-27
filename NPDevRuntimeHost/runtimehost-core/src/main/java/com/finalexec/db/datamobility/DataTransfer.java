package com.finalexec.db.datamobility;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.CurrentTable;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DB -&gt; DB, the third data-mobility direction: streams rows directly from a source connection to
 * a target connection, no file or serialization format in between. Reuses the exact same structure
 * check, DDL-creation, and identifier-quoting logic {@link DataExporter}/{@link DataImporter} use
 * for the file-based directions -- the only real difference is that a row is bound straight from
 * the source {@link ResultSet} into the target {@link PreparedStatement} via {@code getObject}/
 * {@code setObject}, with no text (CSV/SQL-literal) round trip and therefore none of {@link
 * CsvRowFormat}/{@link SqlInsertRowFormat}'s type-re-hydration concerns.
 */
public final class DataTransfer {

    private static final int BATCH_SIZE = 500;

    private DataTransfer() {
    }

    public enum Outcome { TRANSFERRED, NEEDS_CONFIRMATION, BLOCKED }

    public record TransferResult(Outcome outcome, StructureCheckResult structureCheck, Map<String, Long> rowCountsByTable) {
    }

    /**
     * @param sourceEngineKey used only to quote identifiers in the generated {@code SELECT}
     * @param targetEngineKey used both to quote identifiers in the generated {@code INSERT} and,
     *                        via {@link DataMobilityStructureCheck}, to resolve type-rewrite rules
     */
    public static TransferResult transfer(
            DataSource sourceDataSource, Set<String> sourceSystemSchemas, String sourceEngineKey, Set<String> tableScope,
            DataSource targetDataSource, Set<String> targetSystemSchemas, String targetEngineKey,
            boolean includeDdl, boolean confirmed
    ) throws SQLException {
        CurrentSchema sourceFull = new CurrentSchemaReader().read(sourceDataSource, sourceSystemSchemas);
        CurrentSchema source = DataExporter.scope(sourceFull, tableScope);
        CurrentSchema target = new CurrentSchemaReader().read(targetDataSource, targetSystemSchemas);

        StructureCheckResult check = DataMobilityStructureCheck.check(source, target, targetEngineKey, includeDdl);
        if (check.verdict() == StructureVerdict.INCOMPATIBLE) {
            return new TransferResult(Outcome.BLOCKED, check, Map.of());
        }
        if (check.verdict() == StructureVerdict.COMPATIBLE && !confirmed) {
            return new TransferResult(Outcome.NEEDS_CONFIRMATION, check, Map.of());
        }

        if (includeDdl) {
            DataImporter.applyMissingDdl(targetDataSource, targetEngineKey, source, target);
        }

        SqlDialect sourceDialect = SqlDialects.forName(sourceEngineKey);
        SqlDialect targetDialect = SqlDialects.forName(targetEngineKey);
        Map<String, Long> rowCounts = new LinkedHashMap<>();
        try (Connection sourceConnection = sourceDataSource.getConnection();
             Connection targetConnection = targetDataSource.getConnection()) {
            targetConnection.setAutoCommit(false);
            for (Map.Entry<String, CurrentTable> entry : source.tables().entrySet()) {
                long count = transferTable(sourceConnection, targetConnection, entry.getValue(), sourceDialect, targetDialect);
                rowCounts.put(entry.getKey(), count);
            }
            targetConnection.commit();
        }
        return new TransferResult(Outcome.TRANSFERRED, check, rowCounts);
    }

    private static long transferTable(
            Connection sourceConnection, Connection targetConnection, CurrentTable table,
            SqlDialect sourceDialect, SqlDialect targetDialect
    ) throws SQLException {
        List<String> columnNames = new ArrayList<>(table.columns().keySet());
        String selectSql = selectAllSql(table, columnNames, sourceDialect);
        String insertSql = insertSql(table, columnNames, targetDialect);
        long count = 0;
        try (PreparedStatement select = sourceConnection.prepareStatement(selectSql);
             PreparedStatement insert = targetConnection.prepareStatement(insertSql)) {
            select.setFetchSize(500);
            int batched = 0;
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    for (int i = 1; i <= columnNames.size(); i++) {
                        Object value = rs.getObject(i);
                        if (value == null) {
                            insert.setNull(i, java.sql.Types.NULL);
                        } else {
                            insert.setObject(i, value);
                        }
                    }
                    insert.addBatch();
                    count++;
                    if (++batched >= BATCH_SIZE) {
                        insert.executeBatch();
                        batched = 0;
                    }
                }
            }
            if (batched > 0) {
                insert.executeBatch();
            }
        }
        return count;
    }

    private static String selectAllSql(CurrentTable table, List<String> columnNames, SqlDialect dialect) {
        List<String> quoted = new ArrayList<>(columnNames.size());
        for (String columnName : columnNames) {
            quoted.add(dialect.quoteIdentifier(columnName));
        }
        return "SELECT " + String.join(", ", quoted) + " FROM " + dialect.quoteIdentifier(table.name());
    }

    private static String insertSql(CurrentTable table, List<String> columnNames, SqlDialect dialect) {
        List<String> quotedColumns = new ArrayList<>(columnNames.size());
        List<String> placeholders = new ArrayList<>(columnNames.size());
        for (String columnName : columnNames) {
            quotedColumns.add(dialect.quoteIdentifier(columnName));
            placeholders.add("?");
        }
        return "INSERT INTO " + dialect.quoteIdentifier(table.name()) + " (" + String.join(", ", quotedColumns)
                + ") VALUES (" + String.join(", ", placeholders) + ")";
    }
}
