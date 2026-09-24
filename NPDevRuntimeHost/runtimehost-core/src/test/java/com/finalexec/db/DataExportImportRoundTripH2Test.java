package com.finalexec.db;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of {@link ExportMain}/{@link ImportMain} against real H2 databases -- exercises
 * the same seams a real {@code npdev db export}/{@code npdev db import} CLI invocation drives, via
 * their package-private {@code run(args, out, err)} entry points (the same direct-unit-testing seam
 * {@link PromoteMain#run} already establishes for this package's {@code *Main} classes).
 */
class DataExportImportRoundTripH2Test {

    @TempDir
    Path exportDir;

    private String sourceUrl;

    @BeforeEach
    void setUp() {
        SqlDialects.setActive(H2Dialect.INSTANCE);
        sourceUrl = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
    }

    private String freshTargetUrl() {
        return "jdbc:h2:mem:" + getClass().getSimpleName() + "target" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
    }

    private void createSourceSchemaAndData() throws SQLException {
        try (Connection connection = DriverManager.getConnection(sourceUrl); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(64), active BOOLEAN, notes VARCHAR(255))");
            statement.execute("INSERT INTO widgets VALUES (1, 'Alpha', TRUE, NULL)");
            statement.execute("INSERT INTO widgets VALUES (2, 'Beta', FALSE, 'has, a comma')");
            statement.execute("INSERT INTO widgets VALUES (3, 'Gamma', TRUE, 'plain')");
        }
    }

    private int runExport(String format) {
        return ExportMain.run(
                new String[] {"--url", sourceUrl, "--out", exportDir.toString(), "--scope", "all", "--format", format},
                new PrintStream(new ByteArrayOutputStream()), System.err);
    }

    private String runImport(String targetUrl, String format, boolean apply, boolean force) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "--url", targetUrl, "--in", exportDir.toString(), "--format", format));
        if (apply) {
            args.add("--apply");
        }
        if (force) {
            args.add("--force");
        }
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ImportMain.run(args.toArray(new String[0]), new PrintStream(captured), System.err);
        return captured.toString(StandardCharsets.UTF_8);
    }

    private long countRows(String url, String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getLong(1) : -1;
        }
    }

    @Test
    void csvExportThenImportIntoIdenticalSchemaCopiesEveryRow() throws SQLException {
        createSourceSchemaAndData();
        assertEquals(ExportMain.EXIT_OK, runExport("csv"));
        assertTrue(exportDir.resolve("widgets.csv").toFile().isFile());
        assertTrue(exportDir.resolve(DataTransferManifest.FILE_NAME).toFile().isFile());

        String targetUrl = freshTargetUrl();
        try (Connection connection = DriverManager.getConnection(targetUrl); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(64), active BOOLEAN, notes VARCHAR(255))");
        }

        String dryRunOutput = runImport(targetUrl, "csv", false, false);
        assertTrue(dryRunOutput.contains("EQUAL"), dryRunOutput);
        assertEquals(0, countRows(targetUrl, "widgets"));

        String applyOutput = runImport(targetUrl, "csv", true, false);
        assertTrue(applyOutput.contains("imported 3 row(s)"), applyOutput);
        assertEquals(3, countRows(targetUrl, "widgets"));
    }

    @Test
    void compatibleExtraColumnRequiresForceToApply() throws SQLException {
        createSourceSchemaAndData();
        assertEquals(ExportMain.EXIT_OK, runExport("csv"));

        String targetUrl = freshTargetUrl();
        try (Connection connection = DriverManager.getConnection(targetUrl); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(64), active BOOLEAN, notes VARCHAR(255), "
                    + "tag VARCHAR(32))");
        }

        String withoutForce = runImport(targetUrl, "csv", true, false);
        assertTrue(withoutForce.contains("COMPATIBLE"), withoutForce);
        assertTrue(withoutForce.contains("SKIPPED"), withoutForce);
        assertEquals(0, countRows(targetUrl, "widgets"));

        String withForce = runImport(targetUrl, "csv", true, true);
        assertTrue(withForce.contains("imported 3 row(s)"), withForce);
        assertEquals(3, countRows(targetUrl, "widgets"));
    }

    @Test
    void missingTargetColumnIsIncompatibleAndNeverApplies() throws SQLException {
        createSourceSchemaAndData();
        assertEquals(ExportMain.EXIT_OK, runExport("csv"));

        String targetUrl = freshTargetUrl();
        try (Connection connection = DriverManager.getConnection(targetUrl); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        }

        String output = runImport(targetUrl, "csv", true, true);
        assertTrue(output.contains("INCOMPATIBLE"), output);
        assertEquals(0, countRows(targetUrl, "widgets"));
    }

    @Test
    void sqlInsertExportThenImportRunsEveryStatement() throws SQLException {
        createSourceSchemaAndData();
        assertEquals(ExportMain.EXIT_OK, runExport("sql-insert"));
        assertTrue(exportDir.resolve("widgets.sql").toFile().isFile());

        String targetUrl = freshTargetUrl();
        try (Connection connection = DriverManager.getConnection(targetUrl); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(64), active BOOLEAN, notes VARCHAR(255))");
        }

        String output = runImport(targetUrl, "sql-insert", true, false);
        assertTrue(output.contains("ran 3 statement(s)"), output);
        assertEquals(3, countRows(targetUrl, "widgets"));
    }
}
