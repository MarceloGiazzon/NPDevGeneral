package com.npdev.runtime.support.crud.orchestration;

import java.util.List;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): a compiled orchestrationRules entry
 * resolved into its runtime shape (event trigger + ordered actions), ready to be subscribed to the
 * kernel's event bus.
 */
public record RuntimeOrchestration(
        String name,
        String eventName,
        String condition,
        List<RuntimeOrchestrationAction> actions
) {
}
