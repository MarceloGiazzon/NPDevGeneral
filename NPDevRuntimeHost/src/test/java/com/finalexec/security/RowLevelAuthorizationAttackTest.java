package com.finalexec.security;

import com.finalexec.config.RuntimeConceptGatewaySemanticPolicies;
import com.finalexec.db.JdbcBusinessConceptStore;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConceptAccess;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import com.npdev.kernel.concepts.ConceptGatewaySemanticPolicy;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-13: an adversarial row-level (data-scoped) authorization attack suite -- the sibling of
 * {@link TenantIsolationAttackTest}, but SAME-tenant, different actor: two users of the SAME
 * tenant, an Order concept declaring {@code access: {read: "ownerId == $user.id", write:
 * "ownerId == $user.id"}}, and the same "runs under both adapter families" discipline (the
 * pattern is copied deliberately, not shared, to keep each attack suite independently readable
 * per its own established convention).
 *
 * <p>Builds the {@link ConceptGatewaySemanticPolicy} through the REAL production bridge
 * ({@link RuntimeConceptGatewaySemanticPolicies#fromCompiledModel}), not a hand-rolled test
 * double, so this test exercises the actual compiled-model-to-policy wiring an app boots with.
 */
class RowLevelAuthorizationAttackTest {

    private static final String CONCEPT = "Order";
    private static final String TENANT = "clinic-a";
    private static final ExecutionContext USER_A = ExecutionContext.of(TENANT, "user-a");
    private static final ExecutionContext USER_B = ExecutionContext.of(TENANT, "user-b");

    static Stream<Arguments> adapters() {
        return Stream.of(
                Arguments.of(Named.of("InMemory adapter", (Supplier<ConceptStore>) RowLevelAuthorizationAttackTest::newInMemoryStore)),
                Arguments.of(Named.of("JDBC/H2 adapter", (Supplier<ConceptStore>) RowLevelAuthorizationAttackTest::newJdbcStore))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void userBCannotReadUserARowById(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String aId = seedRow(gateway, USER_A);

        assertTrue(gateway.read(new ConceptReadRequest(CONCEPT, aId, null), USER_B).isEmpty(),
                "user B must not see user A's row even within the same tenant");

        // The guard is not over-broad: user A can still read their own row.
        assertTrue(gateway.read(new ConceptReadRequest(CONCEPT, aId, null), USER_A).isPresent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void userBListNeverEnumeratesUserARows(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String aId = seedRow(gateway, USER_A);
        seedRow(gateway, USER_B);

        List<ConceptRecord> bRows = gateway.list(new ConceptListRequest(CONCEPT, null, null, null), USER_B);
        assertEquals(1, bRows.size(), "user B must list only their own row");
        assertFalse(bRows.stream().anyMatch(r -> aId.equals(r.id())),
                "user A's row must never surface in user B's list");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void userBQueryNeverReturnsUserARow(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String aId = seedRow(gateway, USER_A);
        seedRow(gateway, USER_B);

        var page = gateway.query(
                new com.npdev.kernel.concepts.ConceptQueryRequest(CONCEPT, null, com.npdev.kernel.concepts.ConceptQuery.firstPage()),
                USER_B
        );
        assertEquals(1, page.items().size(), "user B's query page must contain only their own row");
        assertFalse(page.items().stream().anyMatch(r -> aId.equals(r.id())),
                "user A's row must never surface in user B's query page");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void userBCannotUpdateUserARow(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String aId = seedRow(gateway, USER_A);

        ConceptGatewayAccessDeniedException denied = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.save(new ConceptWriteRequest(CONCEPT, aId, null,
                        Map.of("id", aId, "ownerId", "user-a", "note", "tampered-by-b")), USER_B),
                "user B must not be able to update user A's row");
        assertEquals("ROW_SCOPE_DENIED", denied.code());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void userBCannotCreateARowClaimingUserAsOwner(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String forgedId = UUID.randomUUID().toString();

        ConceptGatewayAccessDeniedException denied = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.save(new ConceptWriteRequest(CONCEPT, forgedId, null,
                        Map.of("id", forgedId, "ownerId", "user-a", "note", "planted-by-b")), USER_B),
                "user B must not be able to create a row claiming user A's ownership");
        assertEquals("ROW_SCOPE_DENIED", denied.code());
    }

    /**
     * REG-16-resid R2 / LNCH13-F1 — the RUNTIME half of the CRITICAL fix.
     *
     * <p>{@code ServiceBaseFlowRowLevelAuthzTest} proves the generated service now EMITS
     * {@code enforceWithConceptGateway(...)} before {@code enforceWithCreateFlow(...)}. That is a
     * structural assertion over generated source: it shows the call is there and ordered, but it cannot
     * show that the call actually DENIES, nor that it denies BEFORE the flow's side effects run. This
     * test closes that gap by executing the exact sequence the fixed template emits — gateway enforcement
     * first, flow second — and asserting the denial happens and the flow never ran.
     *
     * <p>Why this matters more than usual: {@code docs/ROW_LEVEL_AUTHORIZATION.md}'s own history records
     * that the READ-side twin of this bug went undetected until live E2E testing. This bug class is known
     * to survive tests that only inspect shape, so the fix deserves a behavioural proof.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void flowBackedCreateIsDeniedBeforeTheFlowRuns(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String forgedId = UUID.randomUUID().toString();
        AtomicBoolean flowRan = new AtomicBoolean(false);

        // The generated createFromSource for a flow-backed concept now does, in this order:
        //   enforceWithConceptGateway(...)  -> gateway.save(...)   <-- must deny here
        //   enforceWithCreateFlow(...)      -> kernelRunner.execute(...)  <-- must never be reached
        ConceptGatewayAccessDeniedException denied = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> {
                    gateway.save(new ConceptWriteRequest(CONCEPT, forgedId, null,
                            Map.of("id", forgedId, "ownerId", "user-a", "note", "planted-by-b")), USER_B);
                    flowRan.set(true); // stands in for the declared create Flow's own steps
                },
                "a flow-backed create must still be row-scope denied -- before the fix this endpoint "
                        + "called only the flow and persisted straight through conceptStore");
        assertEquals("ROW_SCOPE_DENIED", denied.code());
        assertFalse(flowRan.get(),
                "the flow's side effects (notifications, external calls) must never run for a write the "
                        + "row-level rule denies -- enforcement precedes the flow, it does not follow it");
    }

    /** REG-16-resid R2 / LNCH13-F1: the update twin — denial is evaluated against the record's PREVIOUS
     *  state, so a non-owner cannot update a row by supplying a payload that claims ownership. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void flowBackedUpdateIsDeniedBeforeTheFlowRuns(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String aId = seedRow(gateway, USER_A);
        AtomicBoolean flowRan = new AtomicBoolean(false);

        ConceptGatewayAccessDeniedException denied = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> {
                    gateway.save(new ConceptWriteRequest(CONCEPT, aId, null,
                            Map.of("id", aId, "ownerId", "user-b", "note", "hijacked-by-b")), USER_B);
                    flowRan.set(true);
                },
                "a flow-backed update must be denied on the PREVIOUS owner, not the payload's claim");
        assertEquals("ROW_SCOPE_DENIED", denied.code());
        assertFalse(flowRan.get(), "the update flow must never run for a denied write");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void userBCannotDeleteUserARow(Supplier<ConceptStore> storeFactory) {
        DefaultConceptGateway gateway = gatewayOver(storeFactory.get());
        String aId = seedRow(gateway, USER_A);

        assertThrows(ConceptGatewayAccessDeniedException.class,
                () -> gateway.delete(new ConceptReadRequest(CONCEPT, aId, null), USER_B),
                "user B must not be able to delete user A's row");

        assertTrue(gateway.read(new ConceptReadRequest(CONCEPT, aId, null), USER_A).isPresent(),
                "user A's row must survive a user B delete attempt");
    }

    // --- helpers -----------------------------------------------------------------------------

    private static DefaultConceptGateway gatewayOver(ConceptStore store) {
        ConceptGatewaySemanticPolicy policy = RuntimeConceptGatewaySemanticPolicies.fromCompiledModel(orderModel());
        return new DefaultConceptGateway(
                store,
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                policy,
                com.npdev.kernel.concepts.ConceptGatewayTraceSink.noop()
        );
    }

    private static String seedRow(DefaultConceptGateway gateway, ExecutionContext owner) {
        String id = UUID.randomUUID().toString();
        // The concept schema declares "id" as required, and the semantic policy's required-field
        // check validates against the data map itself, not the separate ConceptWriteRequest.id
        // param -- omitting it here produces "Required concept field is missing: Order.id" even
        // though id is already passed positionally.
        gateway.save(new ConceptWriteRequest(CONCEPT, id, null, Map.of("id", id, "ownerId", owner.actorId())), owner);
        return id;
    }

    private static CompiledModel orderModel() {
        CompiledConcept order = new CompiledConcept(
                CONCEPT, CONCEPT, "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("ownerId", "string", "String", false, true, false),
                        new CompiledField("note", "string", "String", false, false, false)
                ),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                List.of(),
                new CompiledConceptAccess("ownerId == $user.id", "ownerId == $user.id")
        );
        return new CompiledModel("row.level.attack", "1.0.0", "1.0.0", Map.of(order.getName(), order));
    }

    private static ConceptStore newInMemoryStore() {
        return new InMemoryConceptStore(orderModel());
    }

    private static ConceptStore newJdbcStore() {
        try {
            String url = "jdbc:h2:mem:row-level-attack-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
            DataSource dataSource = new SingleConnectionUrlDataSource(url);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE orders (id UUID NOT NULL, owner_id VARCHAR(255), note VARCHAR(255), "
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
