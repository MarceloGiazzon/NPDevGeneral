package com.finalexec.db;

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

/** Proves the CLI-facing argv wiring end to end -- real H2 databases, real subprocess-shaped
 *  {@code run(args, out, err)} calls, no mocking of the JDBC layer. */
class DataTransferMainTest {

    private static String url(String name) {
        return "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
    }

    private static void exec(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String runCapturingOut(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = DataTransferMain.run(args, new PrintStream(outBuf, true, StandardCharsets.UTF_8), new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        if (code == DataTransferMain.EXIT_COULD_NOT_DETERMINE) {
            throw new AssertionError("EXIT_COULD_NOT_DETERMINE; stderr=" + errBuf);
        }
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void exportThenImportRoundTripsViaTheCliArgvShape(@TempDir Path tempDir) throws Exception {
        String sourceUrl = url("cli_source_" + System.nanoTime());
        String targetUrl = url("cli_target_" + System.nanoTime());
        exec(sourceUrl, "CREATE TABLE \"item\" (\"id\" INT PRIMARY KEY, \"label\" VARCHAR(50))");
        exec(sourceUrl, "INSERT INTO \"item\" VALUES (1, 'first')");
        exec(targetUrl, "CREATE TABLE \"item\" (\"id\" INT PRIMARY KEY, \"label\" VARCHAR(50))");

        String exportOut = runCapturingOut(
                "export", "--url", sourceUrl, "--user", "sa", "--password", "", "--format", "csv", "--tables", "all",
                "--out", tempDir.toString(), "--json");
        assertTrue(exportOut.contains("\"status\":\"ok\""));
        assertTrue(exportOut.contains("\"item\":1"));

        String importOut = runCapturingOut(
                "import", "--bundle", tempDir.toString(), "--format", "csv", "--url", targetUrl,
                "--user", "sa", "--password", "", "--json");
        assertTrue(importOut.contains("\"outcome\":\"IMPORTED\""));
        assertTrue(importOut.contains("\"verdict\":\"EQUAL\""));
    }

    @Test
    void transferMovesRowsDirectlyViaTheCliArgvShape() throws Exception {
        String sourceUrl = url("cli_transfer_source_" + System.nanoTime());
        String targetUrl = url("cli_transfer_target_" + System.nanoTime());
        exec(sourceUrl, "CREATE TABLE \"peer\" (\"id\" INT PRIMARY KEY, \"label\" VARCHAR(50))");
        exec(sourceUrl, "INSERT INTO \"peer\" VALUES (1, 'direct')");
        exec(targetUrl, "CREATE TABLE \"peer\" (\"id\" INT PRIMARY KEY, \"label\" VARCHAR(50))");

        String out = runCapturingOut(
                "transfer", "--source-url", sourceUrl, "--source-user", "sa", "--source-password", "",
                "--target-url", targetUrl, "--target-user", "sa", "--target-password", "",
                "--tables", "all", "--json");
        assertTrue(out.contains("\"outcome\":\"TRANSFERRED\""));
        assertTrue(out.contains("\"peer\":1"));
    }

    @Test
    void structureCheckReportsIncompatibleForATargetMissingATable() throws Exception {
        String sourceUrl = url("cli_sc_source_" + System.nanoTime());
        String targetUrl = url("cli_sc_target_" + System.nanoTime());
        exec(sourceUrl, "CREATE TABLE \"only_in_source\" (\"id\" INT PRIMARY KEY)");
        exec(targetUrl, "CREATE TABLE \"unrelated\" (\"id\" INT PRIMARY KEY)");

        String out = runCapturingOut(
                "structure-check", "--source-url", sourceUrl, "--source-user", "sa", "--source-password", "",
                "--target-url", targetUrl, "--target-user", "sa", "--target-password", "",
                "--tables", "all", "--json");
        assertTrue(out.contains("\"verdict\":\"INCOMPATIBLE\""));
    }

    @Test
    void missingRequiredArgumentReturnsCouldNotDetermineNotAnException() {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = DataTransferMain.run(new String[] {"export"}, new PrintStream(outBuf), new PrintStream(errBuf));
        assertEquals(DataTransferMain.EXIT_COULD_NOT_DETERMINE, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("usage:"));
    }
}
