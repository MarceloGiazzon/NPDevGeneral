package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
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
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 (REAL_LIFT_PLAN_2026-09-03, B5 "real lift" done-when #5): the "boots build N, migrated forward
 * to build N+1's schema, then boots build N again and exercises create/read/update" scenario, at the
 * store level -- the concrete mechanism {@link SchemaCompatibilityVerdict} allows a boot to proceed
 * past. Build N's {@link CompiledModel} never declares {@code bonus_note}; the live table already has
 * it (as build N+1's own additive migration would have added it) -- proving build N's own reads and
 * writes coexist with it rather than merely proving the boot does not refuse.
 */
class JdbcBusinessConceptStoreSchemaAheadCompatibilityTest {

    private DataSource dataSource;
    private JdbcBusinessConceptStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // The live shape build N+1 would have realized: build N's own two columns, PLUS a nullable
            // additive column build N's compiled model below never declares.
            statement.execute("CREATE TABLE products (id UUID NOT NULL, name VARCHAR(255), "
                    + "tenant_id VARCHAR(120) NOT NULL, bonus_note VARCHAR(255), PRIMARY KEY (id))");
        }
        // Build N's compiled model -- unaware bonus_note exists at all.
        CompiledConcept product = new CompiledConcept(
                "Product", "Product", "products",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(product.getName(), product));
        store = new JdbcBusinessConceptStore(dataSource, model);
    }

    @Test
    void createReadUpdateAllSucceedAgainstATableWithAnUndeclaredExtraColumn() {
        String id = UUID.randomUUID().toString();

        // CREATE: must not fail, and must not attempt to touch bonus_note at all.
        store.save(new ConceptRecord("Product", id, "tenant-a", Map.of("name", "Widget")));

        // READ: must succeed, and the returned data() must never surface a column this build does not
        // know about (A3's whole point -- an explicit projection, never `select *`).
        Optional<ConceptRecord> loaded = store.findById("tenant-a", "Product", id);
        assertTrue(loaded.isPresent());
        assertEquals("Widget", loaded.get().data().get("name"));
        assertFalse(loaded.get().data().containsKey("bonusNote"),
                "an undeclared live column must never leak into a record this build reads back: " + loaded.get().data());
        assertFalse(loaded.get().data().containsKey("bonus_note"), loaded.get().data().toString());

        // Seed the extra column with a real value directly (standing in for something build N+1 --
        // or an operator -- wrote there), BEFORE build N's own UPDATE, so the update can prove it
        // leaves that value alone rather than nulling it out.
        setBonusNoteDirectly(id, "set-by-someone-else");

        // UPDATE: must succeed, and must not clobber the column it never even selected.
        store.save(new ConceptRecord("Product", id, "tenant-a", Map.of("name", "Widget v2")));

        assertEquals("Widget v2", store.findById("tenant-a", "Product", id).orElseThrow().data().get("name"));
        assertEquals("set-by-someone-else", readBonusNoteDirectly(id),
                "build N's own update must never overwrite a column outside its own manifest");
    }

    @Test
    void findAllAlsoNeverSurfacesTheUndeclaredColumn() {
        store.save(new ConceptRecord("Product", UUID.randomUUID().toString(), "tenant-a", Map.of("name", "Widget")));

        List<ConceptRecord> all = store.findAll("tenant-a", "Product");

        assertEquals(1, all.size());
        assertFalse(all.get(0).data().containsKey("bonusNote"), all.get(0).data().toString());
    }

    private void setBonusNoteDirectly(String id, String value) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE products SET bonus_note = ? WHERE id = ?")) {
            statement.setString(1, value);
            statement.setObject(2, UUID.fromString(id));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String readBonusNoteDirectly(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT bonus_note FROM products WHERE id = ?")) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

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
