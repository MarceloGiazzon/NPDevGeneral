package com.npdev.kernel.ports;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.execution.FlowInstance;

public interface ExecutionRedactionPolicy {
    ExecutionRedactionPolicy NOOP = (instance, requester) -> instance;

    FlowInstance redact(FlowInstance instance, ExecutionContext requester);
}
