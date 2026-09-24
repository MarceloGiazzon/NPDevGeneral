package com.finalexec.db;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * One file format the data import/export tool can read and write, isolated behind this interface so
 * {@link ExportMain}/{@link ImportMain} never branch on format themselves. {@link CsvRowSerializer}
 * and {@link SqlInsertRowSerializer} are a FORMAT choice, not an engine choice -- both work against
 * any {@code SqlDialect}.
 */
interface RowSerializer {

    String format();

    /** Streams every row of {@code table} to {@code outFile}, using exactly {@code columns} in order. */
    void exportTable(Connection connection, String table, List<DataTransferManifest.ColumnMeta> columns, Path outFile)
            throws SQLException, IOException;

    /**
     * Reads {@code inFile} and inserts every row into {@code table} on {@code connection}. Returns the
     * number of rows/statements executed. The caller (see {@link ImportMain}) is responsible for any
     * pre-import structure check -- this method assumes it has already passed, or was deliberately
     * skipped.
     */
    long importTable(Connection connection, String table, List<DataTransferManifest.ColumnMeta> sourceColumns, Path inFile)
            throws SQLException, IOException;
}
