package com.npdev.kernel.ports;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.trace.FlowTrace;

public interface ExecutionAuthorizationPolicy {
    ExecutionAuthorizationPolicy ALLOW_ALL = new ExecutionAuthorizationPolicy() {
        @Override
        public boolean canExecuteFlow(ExecutionContext requester, String flowName) {
            return true;
        }

        @Override
        public boolean canReadTrace(ExecutionContext requester, FlowTrace trace) {
            return true;
        }

        @Override
        public boolean canSearchTraces(ExecutionContext requester, TraceQuery query) {
            return true;
        }

        @Override
        public boolean canResumeExecution(ExecutionContext requester, FlowInstance instance) {
            return true;
        }

        @Override
        public boolean canPublishEvent(ExecutionContext requester, String eventName, String correlationId) {
            return true;
        }

        @Override
        public boolean canReadExecution(ExecutionContext requester, FlowInstance instance) {
            return true;
        }

        @Override
        public boolean canListExecutions(ExecutionContext requester, String tenantId) {
            return true;
        }

        @Override
        public boolean canReadEvents(ExecutionContext requester, String tenantId) {
            return true;
        }

        @Override
        public boolean canUseDebugView(ExecutionContext requester) {
            return true;
        }

        @Override
        public boolean canReadAudit(ExecutionContext requester) {
            return true;
        }

        @Override
        public boolean canReadFailures(ExecutionContext requester) {
            return true;
        }

        @Override
        public boolean canReadAdminOps(ExecutionContext requester) {
            return true;
        }
    };

    boolean canExecuteFlow(ExecutionContext requester, String flowName);

    boolean canReadTrace(ExecutionContext requester, FlowTrace trace);

    boolean canSearchTraces(ExecutionContext requester, TraceQuery query);

    boolean canResumeExecution(ExecutionContext requester, FlowInstance instance);

    boolean canPublishEvent(ExecutionContext requester, String eventName, String correlationId);

    boolean canReadExecution(ExecutionContext requester, FlowInstance instance);

    boolean canListExecutions(ExecutionContext requester, String tenantId);

    boolean canReadEvents(ExecutionContext requester, String tenantId);

    boolean canUseDebugView(ExecutionContext requester);

    boolean canReadAudit(ExecutionContext requester);

    boolean canReadFailures(ExecutionContext requester);

    boolean canReadAdminOps(ExecutionContext requester);
}
