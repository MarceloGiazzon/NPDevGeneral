package com.npdev.kernel.ports;

import com.npdev.kernel.ExecutionContext;

import java.util.Map;

/**
 * Minimal capability invocation contract used by the phase-2 MVP runner.
 */
public interface CapabilityInvoker {

    Object invoke(
            String capabilityName,
            String adapterId,
            String operationName,
            Object input,
            ExecutionContext executionContext,
            Map<String, Object> currentState
    );
}
