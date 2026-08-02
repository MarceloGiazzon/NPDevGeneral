package com.npdev.adapters.authz.defaultpolicy;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledRole;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultExecutionAuthorizationPolicyTest {

    private final DefaultExecutionAuthorizationPolicy policy = new DefaultExecutionAuthorizationPolicy();

    private static CompiledModel compiledModelWithRoles(CompiledRole... roles) {
        return new CompiledModel(
                "ns", "2.0", "1.0.0", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null,
                List.of(roles)
        );
    }

    @Test
    void deniesAnonymousDefaultRequester() {
        ExecutionContext anonymous = ExecutionContext.anonymous();
        assertFalse(policy.canExecuteFlow(anonymous, "CreateUser"));
        assertFalse(policy.canPublishEvent(anonymous, "UserCreated", "corr-1"));
        assertFalse(policy.canReadFailures(anonymous));
        assertFalse(policy.canReadAdminOps(anonymous));
    }

    @Test
    void deniesTenantIdDefaultEvenWithFullRolesAndPermissions(){
        // ARCH-15: "default" is a reserved sentinel meaning "no tenant registered" -- denial must
        // come from the tenantId itself, not merely from missing roles. A fully-privileged
        // requester registered under the literal tenantId "default" must still be denied.
        ExecutionContext requester = ExecutionContext.of("default", "actor-a").withRoles(Set.of("ADMIN"));
        assertFalse(policy.canExecuteFlow(requester, "CreateUser"));
        assertFalse(policy.canPublishEvent(requester, "UserCreated", "corr-1"));
        assertFalse(policy.canReadFailures(requester));
        assertFalse(policy.canReadAdminOps(requester));

        ExecutionContext caseInsensitive = ExecutionContext.of("DEFAULT", "actor-a").withRoles(Set.of("ADMIN"));
        assertFalse(policy.canExecuteFlow(caseInsensitive, "CreateUser"));
    }

    @Test
    void enforcesTenantIsolationAcrossTraceResumeAndSearch() {
        ExecutionContext requester = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("OPERATOR"));
        ExecutionContext otherTenant = ExecutionContext.of("tenant-b", "actor-b");

        FlowTrace sameTenantTrace = new FlowTrace(
                new FlowTraceMeta("exec-a", "corr-a", "FlowA", "tenant-a", "actor-a", Map.of()),
                1000L,
                1010L,
                StepOutcome.OK,
                List.of()
        );
        FlowTrace otherTenantTrace = new FlowTrace(
                new FlowTraceMeta("exec-b", "corr-b", "FlowB", "tenant-b", "actor-b", Map.of()),
                1000L,
                1010L,
                StepOutcome.OK,
                List.of()
        );

        FlowInstance sameTenantInstance = FlowInstance.start(
                "exec-a",
                "FlowA",
                "corr-a",
                "tenant-a",
                "actor-a",
                Map.of(),
                1000L
        );
        FlowInstance otherTenantInstance = new FlowInstance(
                "exec-b",
                "FlowB",
                "corr-b",
                "tenant-b",
                "actor-b",
                0,
                FlowInstanceStatus.WAITING_EVENT,
                Map.of(),
                "EventX",
                1000L,
                1000L
        );

        TraceQuery scopedQuery = new TraceQuery(null, null, null, null, null, 50, 0, "tenant-a", null);
        TraceQuery crossTenantQuery = new TraceQuery(null, null, null, null, null, 50, 0, "tenant-b", null);

        assertTrue(policy.canReadTrace(requester, sameTenantTrace));
        assertFalse(policy.canReadTrace(requester, otherTenantTrace));
        assertTrue(policy.canReadTrace(otherTenant, otherTenantTrace));

        assertTrue(policy.canResumeExecution(requester, sameTenantInstance));
        assertFalse(policy.canResumeExecution(requester, otherTenantInstance));

        assertTrue(policy.canSearchTraces(requester, scopedQuery));
        assertFalse(policy.canSearchTraces(requester, crossTenantQuery));

        assertTrue(policy.canReadExecution(requester, sameTenantInstance));
        assertFalse(policy.canReadExecution(requester, otherTenantInstance));

        assertTrue(policy.canListExecutions(requester, "tenant-a"));
        assertFalse(policy.canListExecutions(requester, "tenant-b"));

        assertTrue(policy.canReadEvents(requester, "tenant-a"));
        assertFalse(policy.canReadEvents(requester, "tenant-b"));
    }

    @Test
    void debugViewRequiresPrivilegedRoleAndTenant() {
        ExecutionContext user = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("USER"));
        ExecutionContext operator = user.withRoles(Set.of("OPERATOR"));
        ExecutionContext admin = user.withRoles(Set.of("USER", "ADMIN"));
        ExecutionContext missingTenant = ExecutionContext.of("", "actor-a").withRoles(Set.of("ADMIN"));

        assertFalse(policy.canUseDebugView(user));
        assertFalse(policy.canUseDebugView(operator));
        assertTrue(policy.canUseDebugView(admin));
        assertFalse(policy.canUseDebugView(missingTenant));
    }

    @Test
    void auditReadRequiresAdminRoleAndFailureReadSupportsOperator() {
        ExecutionContext user = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("USER"));
        ExecutionContext operator = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("OPERATOR"));
        ExecutionContext admin = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("ADMIN"));

        assertFalse(policy.canReadAudit(user));
        assertFalse(policy.canReadAudit(operator));
        assertTrue(policy.canReadAudit(admin));

        assertFalse(policy.canReadFailures(user));
        assertTrue(policy.canReadFailures(operator));
        assertTrue(policy.canReadFailures(admin));

        assertFalse(policy.canReadAdminOps(user));
        assertFalse(policy.canReadAdminOps(operator));
        assertTrue(policy.canReadAdminOps(admin));
    }

    @Test
    void reg45ResumeRequiresTheOriginatingActorNotJustTheTenant() {
        // REG-45. resumeExecution hands back the ExecutionResult, which carries the flow's accumulated
        // state -- records the flow read under the ORIGINAL actor's row-level access.read scope. So
        // "same tenant" was not a sufficient gate: a colleague holding RESUME_EXECUTIONS could resume
        // someone else's suspended flow and be handed data they could not have read directly.
        DefaultExecutionAuthorizationPolicy policy =
                new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy());

        FlowInstance startedByA = FlowInstance.start(
                "exec-a", "FlowA", "corr-a", "tenant-a", "actor-a", Map.of(), 1000L);

        ExecutionContext actorA = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("OPERATOR"));
        ExecutionContext actorB = ExecutionContext.of("tenant-a", "actor-b").withRoles(Set.of("OPERATOR"));

        assertTrue(policy.canResumeExecution(actorA, startedByA),
                "the originating actor must still be able to resume their own flow");
        assertFalse(policy.canResumeExecution(actorB, startedByA),
                "a different actor in the SAME tenant must not resume it, even holding RESUME_EXECUTIONS");
    }

    @Test
    void reg45AnInstanceWithNoRecordedActorStaysTenantScopedOnly() {
        // FlowInstance normalises a blank actorId to null -- what a flow started anonymously, by the
        // cron scheduler, or before this field was populated looks like. Requiring equality against
        // null would make every one of those permanently unresumable, turning a data-scoping fix into
        // an availability regression for exactly the stuck flows an operator most needs to recover.
        DefaultExecutionAuthorizationPolicy policy =
                new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy());

        FlowInstance ownerless = FlowInstance.start(
                "exec-c", "FlowC", "corr-c", "tenant-a", null, Map.of(), 1000L);

        ExecutionContext operator = ExecutionContext.of("tenant-a", "actor-b").withRoles(Set.of("OPERATOR"));
        ExecutionContext otherTenant = ExecutionContext.of("tenant-b", "actor-b").withRoles(Set.of("OPERATOR"));

        assertTrue(policy.canResumeExecution(operator, ownerless),
                "no recorded owner means there is no owner for actor-scoping to protect");
        assertFalse(policy.canResumeExecution(otherTenant, ownerless),
                "...but tenant isolation still applies");
    }

    /**
     * Wave 3 (RC-B1): GREEN proof -- before the {@code CompiledModel}-aware constructor existed, a
     * requester whose only role was an app-declared one (not USER/OPERATOR/ADMIN) was denied
     * EVERY permission with no way to grant it short of a platform code change.
     */
    @Test
    void appDeclaredRoleFromCompiledModelCanExecuteFlow() {
        CompiledModel compiledModel = compiledModelWithRoles(
                new CompiledRole("WAREHOUSE_MANAGER", List.of("EXECUTE_FLOW", "READ_EXECUTIONS")));
        DefaultExecutionAuthorizationPolicy policyWithRoles =
                new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy(), compiledModel);

        ExecutionContext warehouseManager = ExecutionContext.of("tenant-a", "actor-a")
                .withRoles(Set.of("WAREHOUSE_MANAGER"));

        assertTrue(policyWithRoles.canExecuteFlow(warehouseManager, "PickOrder"));
        assertFalse(policyWithRoles.canReadAudit(warehouseManager),
                "the declared role only grants EXECUTE_FLOW/READ_EXECUTIONS, not READ_AUDIT");

        // Regression: the SAME requester against a policy built with no CompiledModel gets nothing,
        // proving the grant genuinely comes from the app-declared role, not some other change.
        assertFalse(policy.canExecuteFlow(warehouseManager, "PickOrder"));
    }

    /**
     * Wave 3 (RC-B1): a role declaring a grant name that is not a real {@link
     * com.npdev.kernel.auth.Permission} enum constant must fail loudly at construction (app boot),
     * not silently grant nothing the first time a warehouse-manager user makes a request.
     */
    @Test
    void unrecognizedGrantNameFailsAtConstructionNotAtRequestTime() {
        CompiledModel compiledModel = compiledModelWithRoles(
                new CompiledRole("WAREHOUSE_MANAGER", List.of("EXECUTE_FLOW", "TYPO_PERMISSION")));

        assertThrows(IllegalStateException.class,
                () -> new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy(), compiledModel));
    }

    /** Wave 3 (RC-B1) regression: a null CompiledModel (what every pre-existing call site passes,
     *  and what the two-arg constructor delegates to) behaves exactly like today. */
    @Test
    void nullCompiledModelBehavesLikeNoDeclaredRoles() {
        DefaultExecutionAuthorizationPolicy policyWithNullModel =
                new DefaultExecutionAuthorizationPolicy(new DefaultTenantIsolationPolicy(), null);
        ExecutionContext admin = ExecutionContext.of("tenant-a", "actor-a").withRoles(Set.of("ADMIN"));
        assertTrue(policyWithNullModel.canReadAudit(admin));
    }

    /**
     * Move 14 Phase C item C2 (RC-B3), end-to-end through the real constructor + a real (in-memory)
     * identity schema: a runtime-bound permission subset narrows what the declared role grants, and
     * -- the critical safety property -- an override row naming a permission OUTSIDE the role's
     * declared ceiling (READ_AUDIT is not one of WAREHOUSE_MANAGER's two declared grants) still grants
     * nothing beyond the ceiling. Not merely a unit test of {@code RolePermissions} in isolation: this
     * exercises the actual JDBC lookup + the actual policy object together, the same path a real
     * generated app's {@code KernelFacade} calls on every flow-execution/trace/resume request.
     */
    @Test
    void runtimeOverrideNarrowsButNeverExceedsTheDeclaredCeilingThroughTheRealJdbcPath() throws SQLException {
        DataSource dataSource = identitySchemaWithOverride(
                "WarehouseManager", Set.of("EXECUTE_FLOW", "READ_AUDIT"));
        CompiledModel compiledModel = compiledModelWithRoles(
                new CompiledRole("WarehouseManager", List.of("EXECUTE_FLOW", "READ_EXECUTIONS")));
        DefaultExecutionAuthorizationPolicy policyWithOverride = new DefaultExecutionAuthorizationPolicy(
                new DefaultTenantIsolationPolicy(), compiledModel, () -> dataSource);

        ExecutionContext warehouseManager = ExecutionContext.of("tenantx", "charlie")
                .withRoles(Set.of("WarehouseManager"));

        assertTrue(policyWithOverride.canExecuteFlow(warehouseManager, "PickOrder"),
                "EXECUTE_FLOW is in both the ceiling and the override");
        assertFalse(policyWithOverride.canReadAudit(warehouseManager),
                "READ_AUDIT is outside WAREHOUSE_MANAGER's declared ceiling -- the override row must not grant it");
        assertFalse(policyWithOverride.canReadFailures(warehouseManager),
                "canReadFailures needs READ_FAILURES, which is neither in the ceiling nor the override");
    }

    /** A role with no override rows at all keeps its full declared ceiling -- the DataSource is real
     *  and reachable, it simply has nothing configured for this actor. */
    @Test
    void noOverrideRowsMeansFullCeilingEvenWithARealReachableDataSource() throws SQLException {
        DataSource dataSource = identitySchemaWithOverride("WarehouseManager", Set.of());
        CompiledModel compiledModel = compiledModelWithRoles(
                new CompiledRole("WarehouseManager", List.of("EXECUTE_FLOW", "READ_EXECUTIONS")));
        DefaultExecutionAuthorizationPolicy policyWithOverride = new DefaultExecutionAuthorizationPolicy(
                new DefaultTenantIsolationPolicy(), compiledModel, () -> dataSource);

        ExecutionContext warehouseManager = ExecutionContext.of("tenantx", "charlie")
                .withRoles(Set.of("WarehouseManager"));

        assertTrue(policyWithOverride.canExecuteFlow(warehouseManager, "PickOrder"));
        assertTrue(policyWithOverride.canListExecutions(warehouseManager, "tenantx"));
    }

    /** {@code Supplier::get} returning null (no DataSource bean available -- InMemory mode) must
     *  behave exactly like the pre-C2 constructors, never throw. */
    @Test
    void nullDataSourceFromSupplierBehavesLikeNoOverride() {
        CompiledModel compiledModel = compiledModelWithRoles(
                new CompiledRole("WAREHOUSE_MANAGER", List.of("EXECUTE_FLOW")));
        DefaultExecutionAuthorizationPolicy policyWithOverride = new DefaultExecutionAuthorizationPolicy(
                new DefaultTenantIsolationPolicy(), compiledModel, () -> null);

        ExecutionContext warehouseManager = ExecutionContext.of("tenant-a", "actor-a")
                .withRoles(Set.of("WAREHOUSE_MANAGER"));
        assertTrue(policyWithOverride.canExecuteFlow(warehouseManager, "PickOrder"));
    }

    private static DataSource identitySchemaWithOverride(String roleName, Set<String> overridePermissions)
            throws SQLException {
        String url = "jdbc:h2:mem:" + DefaultExecutionAuthorizationPolicyTest.class.getSimpleName()
                + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), active BOOLEAN, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_roles (id UUID PRIMARY KEY, name VARCHAR(120), tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_roles (id UUID PRIMARY KEY, user_id UUID, role_id UUID, tenant_id VARCHAR(120))");
            s.execute("CREATE TABLE identity_user_role_permissions (id UUID PRIMARY KEY, user_role_id UUID, permission VARCHAR(60), tenant_id VARCHAR(120))");
            s.execute("INSERT INTO identity_users VALUES ('11111111-1111-1111-1111-111111111111','charlie',TRUE,'tenantx')");
            s.execute("INSERT INTO identity_roles VALUES ('22222222-2222-2222-2222-222222222222','" + roleName + "','tenantx')");
            s.execute("INSERT INTO identity_user_roles VALUES "
                    + "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','11111111-1111-1111-1111-111111111111',"
                    + "'22222222-2222-2222-2222-222222222222','tenantx')");
            int i = 0;
            for (String permission : overridePermissions) {
                String rowId = String.format("cccccccc-cccc-cccc-cccc-%012d", i++);
                s.execute("INSERT INTO identity_user_role_permissions VALUES "
                        + "('" + rowId + "','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','" + permission + "','tenantx')");
            }
        }
        return dataSource;
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
}
