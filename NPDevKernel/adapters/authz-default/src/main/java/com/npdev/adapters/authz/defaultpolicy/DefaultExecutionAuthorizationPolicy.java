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

    @Override
    public boolean canResumeExecution(ExecutionContext requester, FlowInstance instance) {
        if (!isRequesterAuthorized(requester)
                || !hasPermission(requester, Permission.RESUME_EXECUTIONS)
                || instance == null) {
            return false;
        }
        return tenantIsolationPolicy.sameTenant(requester.tenantId(), instance.tenantId());
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
