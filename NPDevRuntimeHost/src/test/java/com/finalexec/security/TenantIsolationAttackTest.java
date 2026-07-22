package com.finalexec.security;

import com.finalexec.db.JdbcBusinessConceptStore;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-2: an <em>adversarial</em> tenant-isolation attack suite over the generic
 * concept CRUD surface -- the boundary every generated REST controller and the panel
 * runtime funnel through ({@link DefaultConceptGateway} over a {@link ConceptStore}).
 *
 * <p>History shows the two adapter families diverge (isolation bugs #2, #5, #16 all had
 * adapter-specific behavior), so every attack runs under <strong>both</strong> the
 * in-process ({@link InMemoryConceptStore}) and the JDBC ({@link JdbcBusinessConceptStore}
 * on H2) implementations of the same port. This is a permanent ratchet: it lives in the
 * hermetic {@code test} source set so {@code run-runtimehost-gate.ps1} runs it on every
 * pass, with no Docker or Postgres required.
 *
 * <p>Threat model: an authenticated user of tenant B who tries to reach tenant A's data by
 * (1) reading A's row by id, (2) forging {@code tenantId} in the request body to A while
 * authenticated as B, (3) listing/enumerating A's rows, (4) writing into A, and
 * (5) deleting A's row. The gateway must deny cross-tenant intent outright and must never
 * silently operate across the boundary.
 */
class TenantIsolationAttackTest {

    private static final String CONCEPT = "Order";
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String A_SECRET = "tenant-a-classified-payload";
    private static final String B_SECRET = "tenant-b-classified-payload";

    private static final ExecutionContext CTX_A = ExecutionContext.of(TENANT_A, "actor-a");
    private static final ExecutionContext CTX_B = ExecutionContext.of(TENANT_B, "actor-b");

    static Stream<Arguments> adapters() {
        return Stream.of(
                Arguments.of(Named.of("InMemory adapter", (Supplier<ConceptStore>) TenantIsolationAttackTest::newInMemoryStore)),
                Arguments.of(Named.of("JDBC/H2 adapter", (Supplier<ConceptStore>) TenantIsolationAttackTest::newJdbcStore))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void tenantBCannotReadTenantARowById(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String aId = seedRow(gateway, CTX_A, A_SECRET);

        // Scoped to B's own tenant (tenantId omitted): the row simply does not exist for B.
        assertTrue(gateway.read(new ConceptReadRequest(CONCEPT, aId, null), CTX_B).isEmpty(),
                "tenant B must not see tenant A's row through a tenant-scoped read");

        // Forging tenantId=A in the request while authenticated as B: denied outright.
        ConceptGatewayAccessDeniedException denied = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.read(new ConceptReadRequest(CONCEPT, aId, TENANT_A), CTX_B),
                "reading with a forged cross-tenant tenantId must be denied");
        assertEquals("TENANT_SCOPE_DENIED", denied.code());

        // And tenant A can still read its own row -- the guard is not over-broad.
        assertTrue(gateway.read(new ConceptReadRequest(CONCEPT, aId, TENANT_A), CTX_A).isPresent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void tenantBListNeverEnumeratesTenantARows(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        seedRow(gateway, CTX_A, A_SECRET);
        seedRow(gateway, CTX_B, B_SECRET);

        List<ConceptRecord> bRows = gateway.list(new ConceptListRequest(CONCEPT, null, null, null), CTX_B);
        assertEquals(1, bRows.size(), "tenant B must list only its own rows");
        assertFalse(bRows.toString().contains(A_SECRET), "tenant A's payload must never surface in tenant B's list");

        // Forging tenantId=A on the list request is denied, not silently honored.
        assertThrows(ConceptGatewayAccessDeniedException.class,
                () -> gateway.list(new ConceptListRequest(CONCEPT, TENANT_A, null, null), CTX_B),
                "listing with a forged cross-tenant tenantId must be denied");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void tenantBCannotWriteIntoTenantAByForgingTenantId(Supplier<ConceptStore> storeFactory) {
        ConceptStore store = storeFactory.get();
        DefaultConceptGateway gateway = gatewayOver(store);

        String forgedId = UUID.randomUUID().toString();
        ConceptGatewayAccessDeniedException denied = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.save(new ConceptWriteRequest(CONCEPT, forgedId, TENANT_A,
                        Map.of("secret", "planted-by-b")), CTX_B),
                "writing with a forged cross-tenant tenantId must be denied");
        assertEquals("TENANT_SCOPE_DENIED", denied.code());

        // The planted row must not exist under tenant A (defense-in-depth: check the store directly).
        assertTrue(store.findById(TENANT_A, CONCEPT, forgedId).isEmpty(),
                "no cross-tenant row may be written even at the raw store layer");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void tenantBCannotDeleteTenantARow(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String aId = seedRow(gateway, CTX_A, A_SECRET);

        // Forging tenantId=A on the delete is denied outright.
        assertThrows(ConceptGatewayAccessDeniedException.class,
                () -> gateway.delete(new ConceptReadRequest(CONCEPT, aId, TENANT_A), CTX_B),
                "deleting with a forged cross-tenant tenantId must be denied");

        // A tenant-scoped delete by B is a no-op against A's row -- it must survive.
        gateway.delete(new ConceptReadRequest(CONCEPT, aId, null), CTX_B);
        assertTrue(gateway.read(new ConceptReadRequest(CONCEPT, aId, TENANT_A), CTX_A).isPresent(),
                "tenant A's row must survive a tenant B delete attempt");
    }

    // --- helpers -----------------------------------------------------------------------------

    private static DefaultConceptGateway gatewayOver(ConceptStore store) {
        return new DefaultConceptGateway(
                store, PermissionEvaluator.allowAll(), TenantIsolationPolicy.STRICT_EQUALS, AuditLogStore.noop());
    }

    private static String seedRow(DefaultConceptGateway gateway, ExecutionContext owner, String secret) {
        String id = UUID.randomUUID().toString();
        gateway.save(new ConceptWriteRequest(CONCEPT, id, null, Map.of("secret", secret)), owner);
        return id;
    }

    private static CompiledModel orderModel() {
        CompiledConcept order = new CompiledConcept(
                CONCEPT, CONCEPT, "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("secret", "string", "String", false, true, false)
                )
        );
        return new CompiledModel("tenant.isolation.attack", "1.0.0", "1.0.0", Map.of(order.getName(), order));
    }

    private static ConceptStore newInMemoryStore() {
        return new InMemoryConceptStore(orderModel());
    }

    private static ConceptStore newJdbcStore() {
        try {
            String url = "jdbc:h2:mem:tenant-isolation-attack-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
            DataSource dataSource = new SingleConnectionUrlDataSource(url);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE orders (id UUID NOT NULL, secret VARCHAR(255), "
                                + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            }
            return new JdbcBusinessConceptStore(dataSource, orderModel());
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to build H2-backed JDBC concept store", exception);
        }
    }

    /** Minimal DataSource that hands out fresh connections to one in-memory H2 URL. */
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
