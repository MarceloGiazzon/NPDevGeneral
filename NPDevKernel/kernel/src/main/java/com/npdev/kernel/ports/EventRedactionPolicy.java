package com.npdev.kernel.ports;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.events.EventEnvelope;

public interface EventRedactionPolicy {
    EventRedactionPolicy NOOP = (event, requester) -> event;

    EventEnvelope redact(EventEnvelope event, ExecutionContext requester);
}
