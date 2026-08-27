package com.finalexec.db.datamobility;

import com.npdev.kernel.storage.sql.SqlDialects;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the direct DB-to-DB path (no file touched) against two real H2 in-memory databases. */
class DataTransferTest {

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
    void transfersRowsDirectlyBetweenTwoLiveDatabases() throws Exception {
        DataSource source = newDb("transfer_source_" + System.nanoTime());
        DataSource target = newDb("transfer_target_" + System.nanoTime());
        String ddl = "CREATE TABLE \"account\" (\"id\" INT PRIMARY KEY, \"name\" VARCHAR(50), \"balance\" DECIMAL(10,2))";
        exec(source, ddl);
        exec(source, "INSERT INTO \"account\" VALUES (1, 'Ada', 100.50)");
        exec(source, "INSERT INTO \"account\" VALUES (2, 'Grace', NULL)");
        exec(target, ddl);

        DataTransfer.TransferResult result = DataTransfer.transfer(
                source, H2_SYSTEM_SCHEMAS, "h2", Set.of("account"),
                target, H2_SYSTEM_SCHEMAS, "h2", false, false);

        assertEquals(DataTransfer.Outcome.TRANSFERRED, result.outcome());
        assertEquals(StructureVerdict.EQUAL, result.structureCheck().verdict());
        assertEquals(2L, result.rowCountsByTable().get("account"));

        try (Connection c = target.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\", \"name\", \"balance\" FROM \"account\" ORDER BY \"id\"")) {
            assertTrue(rs.next());
            assertEquals("Ada", rs.getString("name"));
            assertEquals(0, java.math.BigDecimal.valueOf(100.50).compareTo(rs.getBigDecimal("balance")));
            assertTrue(rs.next());
            assertEquals("Grace", rs.getString("name"));
            rs.getBigDecimal("balance");
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void blocksWhenTargetIsMissingATableAndIncludeDdlIsOff() throws Exception {
        DataSource source = newDb("transfer_ddl_source_" + System.nanoTime());
        DataSource target = newDb("transfer_ddl_target_" + System.nanoTime());
        exec(source, "CREATE TABLE \"ledger\" (\"id\" INT PRIMARY KEY)");
        exec(source, "INSERT INTO \"ledger\" VALUES (1)");

        DataTransfer.TransferResult blocked = DataTransfer.transfer(
                source, H2_SYSTEM_SCHEMAS, "h2", Set.of("ledger"),
                target, H2_SYSTEM_SCHEMAS, "h2", false, false);
        assertEquals(DataTransfer.Outcome.BLOCKED, blocked.outcome());

        DataTransfer.TransferResult created = DataTransfer.transfer(
                source, H2_SYSTEM_SCHEMAS, "h2", Set.of("ledger"),
                target, H2_SYSTEM_SCHEMAS, "h2", true, true);
        assertEquals(DataTransfer.Outcome.TRANSFERRED, created.outcome());
        assertEquals(1L, created.rowCountsByTable().get("ledger"));
    }
}
