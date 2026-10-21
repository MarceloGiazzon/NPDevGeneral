package com.npdev.generator.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IMP-1: the command-line shell around the introspector. */
class IntrospectDbMainTest {

    private static int run(ByteArrayOutputStream out, ByteArrayOutputStream err, String... args) {
        return IntrospectDbMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void writesTheModelAndTheReportAndSaysWhatItDid(@TempDir Path dir) throws Exception {
        String url = "jdbc:h2:mem:imp1main;DB_CLOSE_DELAY=-1";
        try (Connection keepAlive = DriverManager.getConnection(url, "sa", ""); Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE ITEM (ITEMID INT PRIMARY KEY, ITEMNOME VARCHAR(30))");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = run(out, err, "--url", url, "--user", "sa", "--password", "", "--schema", "PUBLIC",
                    "--namespace", "demo.imp", "--out", dir.resolve("draft").toString());
            assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("1 table(s)"), out.toString(StandardCharsets.UTF_8));
            String model = Files.readString(dir.resolve("draft/model.json"));
            assertTrue(model.contains("\"namespace\" : \"demo.imp\"") && model.contains("\"Item\""), model);
            assertTrue(Files.readString(dir.resolve("draft/import-report.json")).contains("npdev-import-report.v1"));
        }
    }

    @Test
    void missingOrUnknownArgumentsExitTwoWithUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, run(out, err, "--url", "jdbc:h2:mem:x"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("usage:"));
        err.reset();
        assertEquals(2, run(out, err, "--bogus"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown or incomplete argument"));
    }

    @Test
    void anUnreachableDatabaseExitsTwoWithTheDriversMessage(@TempDir Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(out, err, "--url", "jdbc:h2:tcp://127.0.0.1:1/none", "--out", dir.toString());
        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("npdev import db:"));
    }
}
