package com.finalexec.db.schemastate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden test (schema-engine rebuild, P1.3): build a known schema via engine-neutral DDL — a NOT NULL
 * DEFAULT column, a nullable column, a unique constraint, a primary key, a foreign key with ON DELETE
 * CASCADE, and a secondary index — then assert {@link CurrentSchemaReader} reads every dimension back
 * correctly. Run against H2 ({@link CurrentSchemaReaderH2Test}) AND a real Postgres container
 * ({@code CurrentSchemaReaderPostgresTest}): H2 masks type/catalog differences, so the cross-engine
 * proof is the whole point (rule I.1.3).
 */
abstract class AbstractCurrentSchemaReaderGoldenTest {

    private final CurrentSchemaReader reader = new CurrentSchemaReader();

    /** The DataSource under test (fresh in-mem H2, or the shared Postgres container). */
    protected abstract DataSource dataSource();

    @BeforeEach
    void createSchema() throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS orders CASCADE");
            s.execute("DROP TABLE IF EXISTS parent CASCADE");
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
    void dropSchema() throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS orders CASCADE");
            s.execute("DROP TABLE IF EXISTS parent CASCADE");
        }
    }

    @Test
    void readsEveryDimensionOfAKnownSchema() {
        CurrentSchema schema = reader.read(dataSource());

        CurrentTable orders = schema.tables().get("orders");
        assertNotNull(orders, "orders table must be read");
        assertNotNull(schema.tables().get("parent"), "parent table must be read");

        // Columns + nullability + default + varchar length.
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

        // Unique constraint on [email].
        assertTrue(orders.uniques().stream().anyMatch(u -> u.columns().equals(List.of("email"))),
                "unique constraint on [email] must be read: " + orders.uniques());

        // Foreign key parent_id -> parent(id) ON DELETE CASCADE.
        assertEquals(1, orders.foreignKeys().size(), "one FK expected: " + orders.foreignKeys());
        CurrentForeignKey fk = orders.foreignKeys().get(0);
        assertEquals(List.of("parent_id"), fk.columns());
        assertEquals("parent", fk.referencedTable());
        assertEquals(List.of("id"), fk.referencedColumns());
        assertEquals("CASCADE", fk.onDelete(), "ON DELETE CASCADE must be captured");

        // Secondary index on [qty] (PK/unique backing indexes may also appear; don't over-constrain).
        assertTrue(orders.indexes().stream().anyMatch(i -> i.columns().equals(List.of("qty"))),
                "secondary index on [qty] must be read: " + orders.indexes());
    }
}
