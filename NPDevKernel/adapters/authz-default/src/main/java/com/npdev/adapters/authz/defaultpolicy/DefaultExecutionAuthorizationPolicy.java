package com.npdev.adapters.authz.defaultpolicy;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.auth.Permission;
import com.npdev.kernel.auth.RolePermissions;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.ports.ExecutionAuthorizationPolicy;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.trace.FlowTrace;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DefaultExecutionAuthorizationPolicy implements ExecutionAuthorizationPolicy {
    private static final Logger LOG = Logger.getLogger(DefaultExecutionAuthorizationPolicy.class.getName());

    private final TenantIsolationPolicy tenantIsolationPolicy;

    public DefaultExecutionAuthorizationPolicy() {
        this(new DefaultTenantIsolationPolicy());
    }

    public DefaultExecutionAuthorizationPolicy(TenantIsolationPolicy tenantIsolationPolicy) {
        this.tenantIsolationPolicy = Objects.requireNonNull(tenantIsolationPolicy, "tenantIsolationPolicy");
    }

    @Override
    public boolean canExecuteFlow(ExecutionContext requester, String flowName) {
        return isRequesterAuthorized(requester)
                && hasPermission(requester, Permission.EXECUTE_FLOW)
                && flowName != null
                && !flowName.isBlank();
    }

    @Override
    public boolean canReadTrace(ExecutionContext requester, FlowTrace trace) {
        if (!isRequesterAuthorized(requester)
                || !hasPermission(requester, Permission.READ_TRACES)
                || trace == null
                || trace.meta() == null) {
            return false;
        }
        return tenantIsolationPolicy.sameTenant(requester.tenantId(), trace.meta().tenantId());
    }

    @Override
    public boolean canSearchTraces(ExecutionContext requester, TraceQuery query) {
        if (!isRequesterAuthorized(requester)
                || !hasPermission(requester, Permission.READ_TRACES)
                || query == null) {
            return false;
        }
        if (query.tenantId() == null || query.tenantId().isBlank()) {
            return false;
        }
        return tenantIsolationPolicy.sameTenant(requester.tenantId(), query.tenantId());
    }

    /**
     * REG-45: resuming a suspended flow requires the same tenant <b>and the originating actor</b>.
     *
     * <p>Tenant scoping alone was not enough. {@code resumeExecution} returns the resulting
     * {@code ExecutionResult}, which carries the flow's accumulated state — records the flow read
     * under the <em>original</em> actor's row-level {@code access.read} scope. So any holder of
     * {@code RESUME_EXECUTIONS} could resume a colleague's suspended flow and be handed data they
     * could not have read directly. The row-level scoping LNCH-13 enforces on the concept surface had
     * no equivalent on the execution surface; this is it.</p>
     *
     * <p><b>An instance with no recorded actor stays tenant-scoped only.</b> {@code FlowInstance}
     * normalises a blank {@code actorId} to null, which is what a flow started anonymously, by the
     * cron scheduler, or before this field was populated looks like. Requiring equality against null
     * would make every one of those permanently unresumable — turning a data-scoping fix into an
     * availability regression for exactly the stuck flows an operator most needs to recover. Where
     * there is no owner to protect, there is nothing for actor-scoping to add.</p>
     *
     * <p>Only the HTTP resume endpoint consults this policy. The kernel's event-driven and scheduler
     * resume paths do not, so background recovery is unaffected — verified before tightening it.</p>
     */
    @Override
    public boolean canResumeExecution(ExecutionContext requester, FlowInstance instance) {
        if (!isRequesterAuthorized(requester)
                || !hasPermission(requester, Permission.RESUME_EXECUTIONS)
                || instance == null) {
            return false;
        }
        if (!tenantIsolationPolicy.sameTenant(requester.tenantId(), instance.tenantId())) {
            return false;
        }
        String owner = instance.actorId();
        if (owner == null || owner.isBlank()) {
            return true;
        }
        return owner.equals(requester.actorId());
    }

    @Override
    public boolean canPublishEvent(ExecutionContext requester, String eventName, String correlationId) {
        if (!isRequesterAuthorized(requester) || !hasPermission(requester, Permission.PUBLISH_EVENTS)) {
            return false;
        }
        if (eventName == null || eventName.isBlank()) {
            return false;
        }
        return correlationId != null && !correlationId.isBlank();
    }

    @Override
    public boolean canReadExecution(ExecutionContext requester, FlowInstance instance) {
        if (!isRequesterAuthorized(requester)
                || !hasPermission(requester, Permission.READ_EXECUTIONS)
                || instance == null) {
            return false;
        }
        return tenantIsolationPolicy.sameTenant(requester.tenantId(), instance.tenantId());
    }

    @Override
    public boolean canListExecutions(ExecutionContext requester, String tenantId) {
        if (!isRequesterAuthorized(requester)
                || !hasPermission(requester, Permission.READ_EXECUTIONS)
                || tenantId == null
                || tenantId.isBlank()) {
            return false;
        }
        return tenantIsolationPolicy.sameTenant(requester.tenantId(), tenantId);
    }

    @Override
    public boolean canReadEvents(ExecutionContext requester, String tenantId) {
        if (!isRequesterAuthorized(requester)
                || !hasPermission(requester, Permission.READ_EVENTS)
                || tenantId == null
                || tenantId.isBlank()) {
            return false;
        }
        return tenantIsolationPolicy.sameTenant(requester.tenantId(), tenantId);
    }

    @Override
    public boolean canUseDebugView(ExecutionContext requester) {
        return isRequesterAuthorized(requester) && hasPermission(requester, Permission.READ_ADMIN_HEALTH);
    }

    @Override
    public boolean canReadAudit(ExecutionContext requester) {
        return isRequesterAuthorized(requester) && hasPermission(requester, Permission.READ_AUDIT);
    }

    @Override
    public boolean canReadFailures(ExecutionContext requester) {
        return isRequesterAuthorized(requester) && hasPermission(requester, Permission.READ_FAILURES);
    }

    @Override
    public boolean canReadAdminOps(ExecutionContext requester) {
        return isRequesterAuthorized(requester) && hasPermission(requester, Permission.READ_ADMIN_HEALTH);
    }

    private static boolean isRequesterAuthorized(ExecutionContext requester) {
        if (requester == null) {
            return false;
        }
        String tenantId = requester.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        if ("default".equalsIgnoreCase(tenantId)) {
            LOG.log(Level.WARNING, "Denying flow/event/execution authorization for reserved sentinel "
                    + "tenantId \"default\" -- \"default\" is not a usable tenant identity; register a "
                    + "real tenant (POST /api/admin/tenants) and use it instead.");
            return false;
        }
        return true;
    }

    private static boolean hasPermission(ExecutionContext requester, Permission permission) {
        return RolePermissions.hasPermission(requester, permission);
    }
}
