package com.npdev.adapters.persistence.postgres;

import com.npdev.kernel.ports.TenantScope;
import com.npdev.test.postgres.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-50 against a real Postgres server -- the REG-36 lesson (H2-in-Postgres-compatibility-mode does
 * not enforce everything a real engine does) applies here too: whether a coerced identifier is
 * syntactically valid, and whether tenant-scoping fails closed on a genuine metadata-read failure,
 * are both properties of real engine behaviour, not something a fake/mock connection can stand in for
 * on its own -- so every assertion here runs the actual query against a live Postgres container.
 *
 * <p>The "genuine metadata-query failure" this whole finding is about (a transient catalog-read error,
 * as opposed to "this table legitimately has no such column") is simulated with a {@link Proxy} that
 * delegates every {@link Connection} method to a real, otherwise-fully-functional Postgres connection
 * except {@code getMetaData()}, which throws. That is the one thing a real container cannot be made to
 * do on demand (there is no reliable way to force Postgres's own catalog lookup to fail transiently),
 * so this is the correct place -- and the only place -- to fake anything in this test.</p>
 */
class PostgresPersistenceCapabilityAdapterMetadataFailureTest {

    private static final String TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS widgets (
                id UUID NOT NULL,
                owner_id VARCHAR(255),
                note VARCHAR(255),
                tenant_id VARCHAR(120) NOT NULL,
                PRIMARY KEY (id)
            )
            """;

    private DataSource realDataSource;

    @BeforeEach
    void setUp() {
        realDataSource = PostgresTestSupport.dataSource();
        PostgresTestSupport.execute(realDataSource, TABLE_SQL);
        PostgresTestSupport.truncate(realDataSource, "widgets");
    }

    @Test
    void tenantScopedFindByIdFailsClosedWhenMetadataQueryGenuinelyFails() throws SQLException {
        String id = seedWidget("tenant-a");
        PostgresPersistenceCapabilityAdapter adapter =
                new PostgresPersistenceCapabilityAdapter(metadataThrowingDataSource());

        // Before REG-50(a): this fell back to the UNSCOPED findById(concept, id) overload, silently
        // returning tenant-a's row to a caller scoped to tenant-b, indistinguishable from "widgets
        // legitimately has no tenant_id column" (which is not the case here -- it does).
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> adapter.findById(TenantScope.of("tenant-b"), "Widget", UUID.fromString(id)),
                "a genuine metadata-query failure on a table that DOES have tenant_id must deny, "
                        + "not silently fall back to an unscoped read");
        assertTrue(thrown.getMessage().contains("Cannot verify tenant scoping"),
                "unexpected message: " + thrown.getMessage());
    }

    @Test
    void tenantScopedDeleteFailsClosedWhenMetadataQueryGenuinelyFails() {
        String id = seedWidget("tenant-a");
        PostgresPersistenceCapabilityAdapter adapter =
                new PostgresPersistenceCapabilityAdapter(metadataThrowingDataSource());

        assertThrows(IllegalStateException.class,
                () -> adapter.delete(TenantScope.of("tenant-b"), "Widget", UUID.fromString(id)),
                "a genuine metadata-query failure must deny delete, not silently delete cross-tenant");

        // The row must genuinely survive -- proof this was denied, not a delete that happened to
        // affect zero rows for some other reason.
        PostgresPersistenceCapabilityAdapter realAdapter =
                new PostgresPersistenceCapabilityAdapter(realDataSource);
        Object stillThere = realAdapter.findById(TenantScope.of("tenant-a"), "Widget", UUID.fromString(id));
        assertTrue(stillThere != null, "the row must still exist after the denied delete attempt");
    }

    @Test
    void tenantScopedExistsFailsClosedWhenMetadataQueryGenuinelyFails() {
        seedWidget("tenant-a");
        PostgresPersistenceCapabilityAdapter adapter =
                new PostgresPersistenceCapabilityAdapter(metadataThrowingDataSource());

        assertThrows(IllegalStateException.class,
                () -> adapter.exists(TenantScope.of("tenant-b"), "Widget", "ownerId", "owner-a"),
                "a genuine metadata-query failure must deny an existence check too, not silently "
                        + "answer it unscoped");
    }

    @Test
    void hostileFieldNameNeverReachesRawSqlWhenMetadataIsUnavailable() {
        // Real Postgres, real table, but metadata resolution genuinely fails -- resolveCriteriaColumn's
        // fallback (REG-50(b)) is what stands between this input and the SQL string.
        String hostileField = "ownerId'; DROP TABLE widgets; --";
        PostgresPersistenceCapabilityAdapter adapter =
                new PostgresPersistenceCapabilityAdapter(metadataThrowingDataSource());

        // Before the fix: toDbColumn only special-cased uppercase letters, so every character in
        // hostileField above (quote, semicolon, space, dash) passed straight into the SQL string --
        // a genuine "unterminated string literal" syntax error against a real Postgres server
        // (confirmed RED). After the fix, SqlIdentifierSupport.safeSqlIdentifier coerces it to
        // "owner_id_drop_table_widgets" -- a syntactically valid identifier that simply doesn't
        // name a real column, so Postgres reports the normal, safe "column does not exist" instead
        // of a syntax error. That distinction -- benign semantic error vs. syntax-breaking
        // injection -- is exactly what this test proves.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> adapter.exists("Widget", hostileField, "owner-a"));
        String message = thrown.getMessage().toLowerCase();
        assertTrue(message.contains("does not exist"),
                "expected a benign 'column does not exist' error, got: " + thrown.getMessage());
        assertFalse(message.contains("unterminated") || message.contains("syntax"),
                "must never be a syntax-error-shaped failure: " + thrown.getMessage());

        // The table must still exist -- the blunt, unambiguous proof nothing was ever actually
        // executed as separate statements.
        PostgresTestSupport.execute(realDataSource, "SELECT 1 FROM widgets LIMIT 1");
    }

    private String seedWidget(String tenantId) {
        String id = UUID.randomUUID().toString();
        try (Connection c = realDataSource.getConnection();
             var ps = c.prepareStatement("INSERT INTO widgets (id, owner_id, note, tenant_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, UUID.fromString(id));
            ps.setString(2, "owner-a");
            ps.setString(3, "seed");
            ps.setString(4, tenantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to seed widgets row", e);
        }
        return id;
    }

    /** A DataSource whose connections behave exactly like a real Postgres connection, except
     *  {@code getMetaData()} throws -- the one thing that can't be provoked from a real server on
     *  demand, and the only thing faked in this whole test. */
    private DataSource metadataThrowingDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        Connection real = realDataSource.getConnection();
                        return metadataThrowingConnection(real);
                    }
                    return method.invoke(realDataSource, args);
                });
    }

    private Connection metadataThrowingConnection(Connection real) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getMetaData".equals(method.getName())) {
                throw new SQLException("simulated catalog-read failure (REG-50 test)");
            }
            try {
                return method.invoke(real, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }
}
