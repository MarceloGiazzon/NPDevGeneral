package com.finalexec.db.schemastate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden RED test (schema-engine rebuild, P1.3), H2 variant: build a known schema via raw DDL — a
 * NOT NULL DEFAULT column, a nullable column, a unique constraint, a primary key, a foreign key with
 * ON DELETE CASCADE, and a secondary index — then assert {@link CurrentSchemaReader} reads every
 * dimension back correctly. The Postgres twin ({@code CurrentSchemaReaderPostgresTest}) runs the same
 * assertions against a real Postgres container (H2 masks type/catalog differences — rule I.1.3).
 *
 * <p>RED until P1.2: the stub reader returns an empty schema, so every assertion below fails.
 */
class CurrentSchemaReaderH2Test {

    private final CurrentSchemaReader reader = new CurrentSchemaReader();
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE parent (id VARCHAR(36) NOT NULL, PRIMARY KEY (id))");
            s.execute("CREATE TABLE orders ("
                    + "id VARCHAR(36) NOT NULL, "
                    + "name VARCHAR(120) NOT NULL DEFAULT 'x', "
                    + "qty INTEGER, "
                    + "email VARCHAR(200), "
                    + "parent_id VARCHAR(36), "
                    + "PRIMARY KEY (id), "
                    + "CONSTRAINT uq_orders_email UNIQUE (email), "
                    + "CONSTRAINT fk_orders_parent FOREIGN KEY (parent_id) REFERENCES parent (id) ON DELETE CASCADE)");
            s.execute("CREATE INDEX idx_orders_qty ON orders (qty)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void readsEveryDimensionOfAKnownSchema() {
        CurrentSchema schema = reader.read(dataSource);

        CurrentTable orders = schema.tables().get("orders");
        assertNotNull(orders, "orders table must be read");
        assertNotNull(schema.tables().get("parent"), "parent table must be read");

        // Columns + nullability + default.
        CurrentColumn name = orders.columns().get("name");
        assertNotNull(name, "name column must be read");
        assertFalse(name.nullable(), "name is NOT NULL");
        assertNotNull(name.defaultValueNormalized(), "name has a DEFAULT and it must be captured");
        assertNotNull(name.normalizedSqlType(), "name type must be normalized, not null");
        assertEquals(120, name.size(), "varchar(120) length must be captured");

        CurrentColumn qty = orders.columns().get("qty");
        assertNotNull(qty, "qty column must be read");
        assertTrue(qty.nullable(), "qty is nullable");

        // Primary key.
        assertEquals(List.of("id"), orders.primaryKeyColumns(), "PK must be [id]");

        // Unique constraint.
        assertTrue(orders.uniques().stream().anyMatch(u -> u.columns().equals(List.of("email"))),
                "unique constraint on [email] must be read: " + orders.uniques());

        // Foreign key.
        assertEquals(1, orders.foreignKeys().size(), "one FK expected: " + orders.foreignKeys());
        CurrentForeignKey fk = orders.foreignKeys().get(0);
        assertEquals(List.of("parent_id"), fk.columns());
        assertEquals("parent", fk.referencedTable());
        assertEquals(List.of("id"), fk.referencedColumns());
        assertEquals("CASCADE", fk.onDelete(), "ON DELETE CASCADE must be captured");

        // Secondary index (idx_orders_qty on qty). The PK/unique backing indexes may also appear;
        // assert the secondary one is present without over-constraining the full index set.
        assertTrue(orders.indexes().stream().anyMatch(i -> i.columns().equals(List.of("qty"))),
                "secondary index on [qty] must be read: " + orders.indexes());
    }

    /** Minimal {@link DataSource} over {@link DriverManager}; avoids a compile-time H2 dependency. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override public PrintWriter getLogWriter() { return null; }

        @Override public void setLogWriter(PrintWriter out) { }

        @Override public void setLoginTimeout(int seconds) { }

        @Override public int getLoginTimeout() { return 0; }

        @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }

        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }

        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
