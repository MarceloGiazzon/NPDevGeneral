package com.npdev.kernel.ports;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.trace.FlowTrace;

public interface TraceRedactionPolicy {
    TraceRedactionPolicy NOOP = (trace, requester) -> trace;

    FlowTrace redact(FlowTrace trace, ExecutionContext requester);
}
