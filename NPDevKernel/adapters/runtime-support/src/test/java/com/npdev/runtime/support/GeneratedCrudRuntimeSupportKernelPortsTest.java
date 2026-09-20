package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import com.npdev.kernel.security.PermissionDecision;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedCrudRuntimeSupportKernelPortsTest {

    private static final String TENANT = "dev";
    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void resolveCurrentCrudContext_fallsBackToAnonymousWithoutRequest() {
        GeneratedCrudRuntimeSupport support = supportWith(
                AuditLogStore.noop(), PermissionEvaluator.allowAll(), IdempotencyStore.noop());

        ExecutionContext ctx = support.resolveCurrentCrudContext();

        assertEquals("default", ctx.tenantId());
        assertEquals("anonymous", ctx.actorId());
    }

    /**
     * REG-227: before this fix, a revoked token's resolveCurrentExecutionContext silently fell back
     * to ExecutionContext.anonymous() -- which carries DEFAULT_ROLE "USER", not empty roles -- so a
     * stale token could still pass permission checks that allow the USER role, exactly reproducing
     * REG-227's live repro (GET /api/entidades returning 200 instead of 401 after a token_version
     * bump). Mirrors IdentityAwareContextResolverTest's own H2-backed identity_users fixture.
     */
    @Test
    void resolveCurrentCrudContext_throws401WhenTokenVersionIsStale() throws Exception {
        DataSource dataSource = identityDataSource("ada", 5);
        GeneratedCrudRuntimeSupport support = supportWithIdentity(dataSource);
        setRequestClaims(Map.of("tenant_id", TENANT, "actor_id", "ada", "tv", 2));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                support::resolveCurrentCrudContext);

        assertEquals(401, ex.getStatusCode().value());
    }

    /** Regression companion to the test above: a token whose tv still matches must resolve normally. */
    @Test
    void resolveCurrentCrudContext_resolvesNormallyWhenTokenVersionMatches() throws Exception {
        DataSource dataSource = identityDataSource("ada", 5);
        GeneratedCrudRuntimeSupport support = supportWithIdentity(dataSource);
        setRequestClaims(Map.of("tenant_id", TENANT, "actor_id", "ada", "tv", 5));

        ExecutionContext ctx = support.resolveCurrentCrudContext();

        assertEquals(TENANT, ctx.tenantId());
        assertEquals("ada", ctx.actorId());
    }

    /**
     * REG-227's actual live-repro shape: a real JwtBearerAuthFilter-authenticated request carries
     * the RAW JWT payload as claims, whose actor claim is the standard "sub" -- never "actor_id"
     * (that key is RuntimeApiKeyAuthFilter's own convention, confirmed by decoding a real minted
     * token). Before the "sub" fallback, actorId resolved to null for every JWT-mode request, so
     * this exact case silently resolved to ExecutionContext.anonymous() (role "USER") instead of
     * throwing -- reproducing the live symptom (GET /api/entidades returning 200, not 401) directly,
     * not just the narrower actor_id-keyed case the test above covers.
     */
    @Test
    void resolveCurrentCrudContext_throws401ForStaleTokenUsingRealJwtSubClaim() throws Exception {
        DataSource dataSource = identityDataSource("admin", 3);
        GeneratedCrudRuntimeSupport support = supportWithIdentity(dataSource);
        setRequestClaims(Map.of("tenant_id", TENANT, "sub", "admin", "tv", 0, "roles", List.of("ADMIN")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                support::resolveCurrentCrudContext);

        assertEquals(401, ex.getStatusCode().value());
    }

    private static void setRequestClaims(Map<String, Object> claims) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(CLAIMS_ATTRIBUTE, claims);
        HttpServletRequest request = fakeRequest(attributes);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** Minimal getAttribute-only HttpServletRequest -- everything else is unused by the code under
     * test, so a JDK dynamic proxy avoids hand-implementing the interface's several dozen methods. */
    private static HttpServletRequest fakeRequest(Map<String, Object> attributes) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getAttribute":
                    return attributes.get(args[0]);
                case "setAttribute":
                    attributes.put((String) args[0], args[1]);
                    return null;
                case "toString":
                    return "fakeRequest";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultReturnValue(method.getReturnType());
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                GeneratedCrudRuntimeSupportKernelPortsTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, handler);
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        return 0;
    }

    private static CompiledModel identityModel() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        return new CompiledModel("test", "1.0.0", concepts);
    }

    private static DataSource identityDataSource(String username, int tokenVersion) throws SQLException {
        String url = "jdbc:h2:mem:" + GeneratedCrudRuntimeSupportKernelPortsTest.class.getSimpleName()
                + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "tenant_id VARCHAR(120), active BOOLEAN, token_version INT)");
            statement.execute("INSERT INTO identity_users VALUES (RANDOM_UUID(), '" + username + "', '"
                    + TENANT + "', TRUE, " + tokenVersion + ")");
        }
        return dataSource;
    }

    private static GeneratedCrudRuntimeSupport supportWithIdentity(DataSource dataSource) {
        KernelRunner kernelRunner = new KernelRunner(
                (EventBus) event -> {},
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }
        );
        return new GeneratedCrudRuntimeSupport(
                identityModel(),
                kernelRunner,
                null,
                null,
                null,
                dataSource,
                new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                new RuntimeInvariantEngineFactory() {
                    @Override
                    public InvariantEngine create(
                            RuntimeInvariantEngineFactory.UniqueValueLookup uniqueValueLookup,
                            RuntimeInvariantEngineFactory.ConflictLookup conflictLookup
                    ) {
                        return new InvariantEngine() {
                            @Override
                            public List<String> evaluate(String entityName, Object payload) {
                                return List.of();
                            }
                        };
                    }
                },
                AuditLogStore.noop(),
                PermissionEvaluator.allowAll(),
                IdempotencyStore.noop()
        );
    }

    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    @Test
    void checkCrudPermission_throwsAndAuditsDenyWhenDenied() {
        List<AuditRecord> captured = new ArrayList<>();
        AuditLogStore capturingStore = new AuditLogStore() {
            @Override
            public void append(AuditRecord record) {
                captured.add(record);
            }

            @Override
            public List<AuditRecord> search(AuditQuery query) {
                return List.of();
            }
        };
        PermissionEvaluator denyAll = (subject, requirement) -> PermissionDecision.deny("test_deny", "denied");

        GeneratedCrudRuntimeSupport support = supportWith(capturingStore, denyAll, IdempotencyStore.noop());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                support.checkCrudPermission("Order", "CREATE", ExecutionContext.anonymous()));

        assertEquals(403, ex.getStatusCode().value());
        assertEquals(1, captured.size(), "audit must be called before throwing");
        assertEquals("DENY", captured.get(0).outcome());
    }

    @Test
    void auditCrudMutation_doesNotThrowWhenAuditStoreFails() {
        AuditLogStore failingStore = new AuditLogStore() {
            @Override
            public void append(AuditRecord record) {
                throw new RuntimeException("store down");
            }

            @Override
            public List<AuditRecord> search(AuditQuery query) {
                return List.of();
            }
        };

        GeneratedCrudRuntimeSupport support = supportWith(
                failingStore, PermissionEvaluator.allowAll(), IdempotencyStore.noop());

        assertDoesNotThrow(() ->
                support.auditCrudMutation("Order", "CREATE", UUID.randomUUID().toString(), "ALLOW",
                        ExecutionContext.anonymous()));
    }

    /**
     * R5.1: the 7-arg {@code auditCrudMutation} overload is what makes the audit trail record WHAT
     * changed, not just that a mutation happened -- proves the recorded {@link AuditRecord} carries a
     * "field: before -> after" entry per changed field, an unchanged field is silently omitted (no
     * noise), and a create's "before == null" fields all show as "null -> value".
     */
    @Test
    void auditCrudMutation_withBeforeAndAfter_recordsOnlyChangedFieldsInDiff() {
        List<AuditRecord> captured = new ArrayList<>();
        AuditLogStore capturingStore = new AuditLogStore() {
            @Override
            public void append(AuditRecord record) {
                captured.add(record);
            }

            @Override
            public List<AuditRecord> search(AuditQuery query) {
                return List.of();
            }
        };
        GeneratedCrudRuntimeSupport support = supportWith(
                capturingStore, PermissionEvaluator.allowAll(), IdempotencyStore.noop());

        java.util.Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "OPEN");
        before.put("priority", 1);
        before.put("id", "order-1");

        java.util.Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CLOSED");
        after.put("priority", 1);
        after.put("id", "order-1");

        support.auditCrudMutation("Order", "UPDATE", "order-1", "ALLOW", ExecutionContext.anonymous(),
                before, after);

        assertEquals(1, captured.size());
        AuditRecord record = captured.get(0);
        assertEquals("CRUD_UPDATE", record.action());
        String diff = record.meta().get("fieldDiff");
        assertTrue(diff.contains("status: OPEN -> CLOSED"), diff);
        assertFalse(diff.contains("priority"), "unchanged field must not appear in the diff: " + diff);
        assertFalse(diff.contains("id:"), "unchanged field must not appear in the diff: " + diff);
    }

    @Test
    void auditCrudMutation_createHasNoBefore_everyAfterFieldShowsAsNullToValue() {
        List<AuditRecord> captured = new ArrayList<>();
        AuditLogStore capturingStore = new AuditLogStore() {
            @Override
            public void append(AuditRecord record) {
                captured.add(record);
            }

            @Override
            public List<AuditRecord> search(AuditQuery query) {
                return List.of();
            }
        };
        GeneratedCrudRuntimeSupport support = supportWith(
                capturingStore, PermissionEvaluator.allowAll(), IdempotencyStore.noop());

        java.util.Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "OPEN");

        support.auditCrudMutation("Order", "CREATE", "order-2", "ALLOW", ExecutionContext.anonymous(),
                null, after);

        String diff = captured.get(0).meta().get("fieldDiff");
        assertTrue(diff.contains("status: null -> OPEN"), diff);
    }

    @Test
    void auditCrudMutation_noBeforeOrAfter_omitsFieldDiffFromMeta() {
        List<AuditRecord> captured = new ArrayList<>();
        AuditLogStore capturingStore = new AuditLogStore() {
            @Override
            public void append(AuditRecord record) {
                captured.add(record);
            }

            @Override
            public List<AuditRecord> search(AuditQuery query) {
                return List.of();
            }
        };
        GeneratedCrudRuntimeSupport support = supportWith(
                capturingStore, PermissionEvaluator.allowAll(), IdempotencyStore.noop());

        support.auditCrudMutation("Order", "RESTORE", "order-3", "ALLOW", ExecutionContext.anonymous());

        assertFalse(captured.get(0).meta().containsKey("fieldDiff"),
                "no before/after supplied -- meta must not carry a fieldDiff key at all");
    }

    @Test
    void checkCrudIdempotency_returnsExistingWhenKeyRecorded() {
        String storedId = UUID.randomUUID().toString();
        IdempotencyRecord record = new IdempotencyRecord(
                "default", "key-abc", "crud.Order", "create",
                System.currentTimeMillis(), IdempotencyRecord.STATUS_SUCCESS, storedId, null);

        IdempotencyStore recordingStore = new IdempotencyStore() {
            @Override
            public Optional<IdempotencyRecord> find(String tenantId, String capability,
                                                     String operation, String idempotencyKey) {
                return Optional.of(record);
            }

            @Override
            public void saveSuccess(String tenantId, String capability, String operation,
                                    String idempotencyKey, String resultJsonRedacted, long createdAtMs) {
            }

            @Override
            public void saveFailure(String tenantId, String capability, String operation,
                                    String idempotencyKey, String errorCode, long createdAtMs) {
            }
        };

        GeneratedCrudRuntimeSupport support = supportWith(
                AuditLogStore.noop(), PermissionEvaluator.allowAll(), recordingStore);

        Optional<String> result = support.checkCrudIdempotency("default", "Order", "key-abc");

        assertTrue(result.isPresent());
        assertEquals(storedId, result.get());
    }

    @Test
    void recordCrudIdempotencySuccess_noopsWhenKeyBlank() {
        boolean[] saveCalled = {false};
        IdempotencyStore spyStore = new IdempotencyStore() {
            @Override
            public Optional<IdempotencyRecord> find(String tenantId, String capability,
                                                     String operation, String idempotencyKey) {
                return Optional.empty();
            }

            @Override
            public void saveSuccess(String tenantId, String capability, String operation,
                                    String idempotencyKey, String resultJsonRedacted, long createdAtMs) {
                saveCalled[0] = true;
            }

            @Override
            public void saveFailure(String tenantId, String capability, String operation,
                                    String idempotencyKey, String errorCode, long createdAtMs) {
            }
        };

        GeneratedCrudRuntimeSupport support = supportWith(
                AuditLogStore.noop(), PermissionEvaluator.allowAll(), spyStore);

        support.recordCrudIdempotencySuccess("default", "Order", UUID.randomUUID().toString(), "   ");

        assertFalse(saveCalled[0], "saveSuccess must not be called for blank idempotencyKey");
    }

    private static GeneratedCrudRuntimeSupport supportWith(
            AuditLogStore auditLogStore,
            PermissionEvaluator permissionEvaluator,
            IdempotencyStore idempotencyStore) {

        CompiledModel compiledModel = new CompiledModel("demo", "1.0.0", "v1", new LinkedHashMap<>());
        KernelRunner kernelRunner = new KernelRunner(
                (EventBus) event -> {},
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }
        );

        return new GeneratedCrudRuntimeSupport(
                compiledModel,
                kernelRunner,
                null,
                null,
                null,
                null,
                new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                new RuntimeInvariantEngineFactory() {
                    @Override
                    public InvariantEngine create(
                            RuntimeInvariantEngineFactory.UniqueValueLookup uniqueValueLookup,
                            RuntimeInvariantEngineFactory.ConflictLookup conflictLookup
                    ) {
                        return new InvariantEngine() {
                            @Override
                            public List<String> evaluate(String entityName, Object payload) {
                                return List.of();
                            }
                        };
                    }
                },
                auditLogStore,
                permissionEvaluator,
                idempotencyStore
        );
    }
}
