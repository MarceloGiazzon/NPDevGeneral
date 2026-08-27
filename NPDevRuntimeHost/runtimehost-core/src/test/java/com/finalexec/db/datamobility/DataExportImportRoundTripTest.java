package com.finalexec.db.datamobility;

import com.npdev.kernel.storage.sql.SqlDialects;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the DB -&gt; file -&gt; DB path end to end against real H2 in-memory databases, for both
 * export formats -- this is the "did I actually verify it, not just get it to compile" proof this
 * repo's own culture insists on (CLAUDE.md's storage section, the STOR-4/5/7/9/10/11/12 lesson).
 *
 * <p>Every table/column name in this fixture's raw DDL/DML is double-quoted lowercase. That's not
 * decorative: {@code DataExporter}/{@code DataImporter} quote every identifier they send to a real
 * engine via {@code SqlDialect.quoteIdentifier} (so a real user's {@code order}/{@code group}-named
 * column survives), and H2's default UNQUOTED-identifier folding is UPPERCASE -- unlike Postgres,
 * which folds to lowercase (see {@code SqlDialect.foldsUnquotedIdentifiersToLowerCase}'s own
 * javadoc). An unquoted {@code CREATE TABLE widget (...)} really creates {@code WIDGET} on H2, so a
 * later quoted {@code "widget"} reference would not find it -- a mismatch specific to hand-written
 * ad-hoc test SQL, not to how NPDev's own generated schemas are shaped (those are always written
 * lowercase to begin with).
 */
class DataExportImportRoundTripTest {

    private static final Set<String> H2_SYSTEM_SCHEMAS = SqlDialects.forName("h2").systemSchemas();

    private static DataSource newDb(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static void exec(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void csvRoundTripMovesEveryRowIntoAMatchingTargetSchema(@TempDir Path tempDir) throws Exception {
        DataSource source = newDb("csv_rt_source_" + System.nanoTime());
        DataSource target = newDb("csv_rt_target_" + System.nanoTime());
        String ddl = "CREATE TABLE \"widget\" (\"id\" INT PRIMARY KEY, \"name\" VARCHAR(100), \"active\" BOOLEAN, \"price\" DECIMAL(10,2))";
        exec(source, ddl);
        exec(source, "INSERT INTO \"widget\" VALUES (1, 'Alpha', TRUE, 9.99)");
        exec(source, "INSERT INTO \"widget\" VALUES (2, 'Beta, the second', FALSE, NULL)");
        exec(target, ddl);

        DataExporter.ExportResult exported = DataExporter.export(
                source, H2_SYSTEM_SCHEMAS, "h2", Set.of("widget"), DataExporter.Format.CSV, tempDir);
        assertEquals(2L, exported.rowCountsByTable().get("widget"));

        DataImporter.ImportResult imported = DataImporter.importFrom(
                tempDir, DataExporter.Format.CSV, target, H2_SYSTEM_SCHEMAS, "h2", false, false);
        assertEquals(DataImporter.Outcome.IMPORTED, imported.outcome());
        assertEquals(StructureVerdict.EQUAL, imported.structureCheck().verdict());
        assertEquals(2L, imported.rowCountsByTable().get("widget"));

        try (Connection c = target.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\", \"name\", \"active\", \"price\" FROM \"widget\" ORDER BY \"id\"")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("id"));
            assertEquals("Alpha", rs.getString("name"));
            assertTrue(rs.getBoolean("active"));
            assertEquals(0, java.math.BigDecimal.valueOf(9.99).compareTo(rs.getBigDecimal("price")));
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("id"));
            assertEquals("Beta, the second", rs.getString("name"));
            assertTrue(!rs.getBoolean("active"));
            rs.getBigDecimal("price");
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void sqlRoundTripMovesEveryRowIntoAMatchingTargetSchema(@TempDir Path tempDir) throws Exception {
        DataSource source = newDb("sql_rt_source_" + System.nanoTime());
        DataSource target = newDb("sql_rt_target_" + System.nanoTime());
        String ddl = "CREATE TABLE \"gadget\" (\"id\" INT PRIMARY KEY, \"label\" VARCHAR(100))";
        exec(source, ddl);
        exec(source, "INSERT INTO \"gadget\" VALUES (1, 'it''s quoted')");
        exec(source, "INSERT INTO \"gadget\" VALUES (2, NULL)");
        exec(target, ddl);

        DataExporter.export(source, H2_SYSTEM_SCHEMAS, "h2", Set.of("gadget"), DataExporter.Format.SQL, tempDir);

        DataImporter.ImportResult imported = DataImporter.importFrom(
                tempDir, DataExporter.Format.SQL, target, H2_SYSTEM_SCHEMAS, "h2", false, false);
        assertEquals(DataImporter.Outcome.IMPORTED, imported.outcome());
        assertEquals(2L, imported.rowCountsByTable().get("gadget"));

        try (Connection c = target.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\", \"label\" FROM \"gadget\" ORDER BY \"id\"")) {
            assertTrue(rs.next());
            assertEquals("it's quoted", rs.getString("label"));
            assertTrue(rs.next());
            assertEquals(null, rs.getString("label"));
        }
    }

    @Test
    void missingTargetTableIsIncompatibleWithoutIncludeDdlAndCreatedWithIt(@TempDir Path tempDir) throws Exception {
        DataSource source = newDb("ddl_rt_source_" + System.nanoTime());
        DataSource target = newDb("ddl_rt_target_" + System.nanoTime());
        exec(source, "CREATE TABLE \"thing\" (\"id\" INT PRIMARY KEY, \"note\" VARCHAR(50))");
        exec(source, "INSERT INTO \"thing\" VALUES (1, 'hello')");
        // target has no `thing` table at all yet.

        DataExporter.export(source, H2_SYSTEM_SCHEMAS, "h2", Set.of("thing"), DataExporter.Format.CSV, tempDir);

        DataImporter.ImportResult blocked = DataImporter.importFrom(
                tempDir, DataExporter.Format.CSV, target, H2_SYSTEM_SCHEMAS, "h2", false, false);
        assertEquals(DataImporter.Outcome.BLOCKED, blocked.outcome());
        assertEquals(StructureVerdict.INCOMPATIBLE, blocked.structureCheck().verdict());

        DataImporter.ImportResult created = DataImporter.importFrom(
                tempDir, DataExporter.Format.CSV, target, H2_SYSTEM_SCHEMAS, "h2", true, true);
        assertEquals(DataImporter.Outcome.IMPORTED, created.outcome());
        assertEquals(1L, created.rowCountsByTable().get("thing"));
    }

    @Test
    void extraTargetColumnIsCompatibleAndRequiresConfirmation(@TempDir Path tempDir) throws Exception {
        DataSource source = newDb("compat_rt_source_" + System.nanoTime());
        DataSource target = newDb("compat_rt_target_" + System.nanoTime());
        exec(source, "CREATE TABLE \"note\" (\"id\" INT PRIMARY KEY, \"body\" VARCHAR(50))");
        exec(source, "INSERT INTO \"note\" VALUES (1, 'hi')");
        exec(target, "CREATE TABLE \"note\" (\"id\" INT PRIMARY KEY, \"body\" VARCHAR(50), \"extra_col\" VARCHAR(20))");

        DataExporter.export(source, H2_SYSTEM_SCHEMAS, "h2", Set.of("note"), DataExporter.Format.CSV, tempDir);

        DataImporter.ImportResult needsConfirm = DataImporter.importFrom(
                tempDir, DataExporter.Format.CSV, target, H2_SYSTEM_SCHEMAS, "h2", false, false);
        assertEquals(DataImporter.Outcome.NEEDS_CONFIRMATION, needsConfirm.outcome());
        assertEquals(StructureVerdict.COMPATIBLE, needsConfirm.structureCheck().verdict());

        DataImporter.ImportResult confirmed = DataImporter.importFrom(
                tempDir, DataExporter.Format.CSV, target, H2_SYSTEM_SCHEMAS, "h2", false, true);
        assertEquals(DataImporter.Outcome.IMPORTED, confirmed.outcome());
        assertEquals(1L, confirmed.rowCountsByTable().get("note"));
    }
}
