package com.finalexec.db;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "RuntimeHost generated-app Postgres integration tests" CI failure: {@code canonical-demo}'s
 * ApplicationContext failed to boot with {@code PSQLException: relation "patients" does not exist}
 * inside {@link MissingTableCreationPass#createBusinessTableConstraints}. The original one-phase
 * {@code createMissingBusinessTables} created each table and immediately added its FK constraints
 * in the SAME loop iteration, in whatever order the manifest's {@code businessTableColumns} map
 * happened to iterate -- a table whose FK {@code REFERENCES} a table that hasn't had its turn yet
 * fails the {@code ALTER TABLE}. Postgres surfaces this every time (its {@code information_schema}
 * iteration order does not happen to match H2's, which is why this was invisible locally).
 *
 * <p>This test forces exactly that ordering (the FK-holding table listed BEFORE its referenced
 * table in the manifest) to prove the two-phase fix -- create every missing table first, then add
 * every constraint -- is order-independent.
 */
class MissingTableCreationPassTest {

    private String url;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:missing-table-creation-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        // SqlDialects.active() defaults to Postgres and memoizes the first resolution for the whole
        // JVM -- pin H2 explicitly so the DDL this test issues matches the H2 connection it runs against.
        SqlDialects.setActive(H2Dialect.INSTANCE);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        SqlDialects.resetActiveForTesting();
    }

    @Test
    void createsEveryTableBeforeAnyConstraintRegardlessOfDeclarationOrder() throws SQLException {
        // "orders" (which FKs to "widgets") is listed FIRST in businessTableColumns -- the exact
        // ordering the original bug needed to fail: its FK constraint would have been attempted
        // immediately, before "widgets" had been created yet.
        Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("orders", List.of("id", "widget_id"));
        columns.put("widgets", List.of("id"));

        Map<String, List<SchemaLifecycleExecutor.ForeignKeyDecl>> foreignKeys = Map.of(
                "orders", List.of(new SchemaLifecycleExecutor.ForeignKeyDecl(
                        List.of("widget_id"), "widgets", List.of("id"))));

        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "Postgres", "jdbc", true, "fp-1", List.of(), List.of("orders", "widgets"),
                columns, Map.of(), Map.of(), Map.of(), Map.of(), false,
                "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "",
                Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), "NpdevManaged", foreignKeys, Map.of(), Map.of());

        assertDoesNotThrow(() -> MissingTableCreationPass.createMissingBusinessTables(dataSource(), manifest));
        assertTrue(foreignKeyExists("orders", "widget_id", "widgets"),
                "expected a real FK from orders.widget_id to widgets");
    }

    private boolean foreignKeyExists(String table, String column, String referencedTable) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            try (ResultSet rs = connection.getMetaData().getImportedKeys(null, null, table)) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("FKCOLUMN_NAME"))
                            && referencedTable.equalsIgnoreCase(rs.getString("PKTABLE_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private DataSource dataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url);
            }

            @Override
            public Connection getConnection(String username, String password) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }
}
