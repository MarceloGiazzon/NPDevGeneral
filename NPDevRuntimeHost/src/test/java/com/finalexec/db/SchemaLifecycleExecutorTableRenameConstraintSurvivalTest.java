package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 P2 (2.5) explicit VERIFY item: proves, against a real H2 database, that renaming the
 * REFERENCED side of a foreign key (mirroring how NPDev bonds compile to FKs -- a plain
 * {@code REFERENCES} clause in {@code CREATE TABLE} is sufficient to exercise the same rename
 * path a bond-backed FK would go through) leaves both the foreign-key relationship AND a secondary
 * unique index on the renamed table fully intact and still enforcing:
 * <ul>
 *   <li>Existing referencing rows still resolve via a join after the rename (no orphaned FK).</li>
 *   <li>The FK constraint itself still enforces: inserting a referencing row that points at a
 *       non-existent id still fails after the rename, exactly as it did before.</li>
 *   <li>The secondary unique index on the renamed table still exists and still enforces: inserting
 *       a duplicate value still fails after the rename, exactly as it did before.</li>
 * </ul>
 * Per the plan's own read of Postgres/H2 rename semantics these should all survive (H2/Postgres
 * `ALTER TABLE ... RENAME TO` keeps constraints and indexes attached, just under their old-prefixed
 * names) -- this test exists to catch reality being different from that assumption, not because
 * failure is expected.
 */
class SchemaLifecycleExecutorTableRenameConstraintSurvivalTest {

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void renamingTheReferencedTablePreservesForeignKeyAndSecondaryUniqueIndex() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (id BIGINT PRIMARY KEY, email VARCHAR(100), version BIGINT)");
            statement.execute("CREATE UNIQUE INDEX ux_customers_email ON customers (email)");
            statement.execute(
                    "CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id BIGINT, version BIGINT, "
                            + "CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id))");
            statement.execute("INSERT INTO customers (id, email, version) VALUES (1, 'ada@example.com', 1)");
            statement.execute("INSERT INTO orders (id, customer_id, version) VALUES (100, 1, 1)");
        }

        // Rename the REFERENCED table (customers -> clients). Bonds always compile to a FK on the
        // REFERENCING side pointing at the target concept's table, so this is the direction that
        // matters: does a bonded table survive its target being renamed?
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of(
                        "clients", List.of("id", "email", "version"),
                        "orders", List.of("id", "customer_id", "version")
                ),
                Map.of("clients", "customers"));

        executor.attemptInPlaceTableRenames(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            // 1. Existing referencing rows still resolve via a join after the rename.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT c.email FROM orders o JOIN clients c ON o.customer_id = c.id WHERE o.id = ?")) {
                statement.setLong(1, 100L);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "the order must still join to its customer after the rename");
                    assertEquals("ada@example.com", resultSet.getString(1));
                }
            }

            // 2. The FK constraint still enforces: a referencing row pointing at a non-existent id
            // must still fail after the rename, exactly as it did before.
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO orders (id, customer_id, version) VALUES (101, 999, 1)")) {
                    statement.executeUpdate();
                }
            }, "the foreign key must still be enforced against the renamed (now 'clients') table");

            // 3. The secondary unique index on the renamed table still exists and still enforces: a
            // duplicate email must still fail after the rename.
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO clients (id, email, version) VALUES (2, 'ada@example.com', 1)")) {
                    statement.executeUpdate();
                }
            }, "the secondary unique index on the renamed table must still enforce uniqueness");
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, String> businessTableRenames) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                Map.of(),
                Map.of(),
                Map.of(),
                businessTableRenames,
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED"
        );
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in an H2-specific compile-time dependency. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
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
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
