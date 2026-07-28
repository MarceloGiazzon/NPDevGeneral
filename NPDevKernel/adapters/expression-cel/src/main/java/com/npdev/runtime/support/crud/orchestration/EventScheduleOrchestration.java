package com.npdev.runtime.support.crud.orchestration;

import java.util.Map;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the resolved shape of a declarative
 * orchestrationRules {@code scheduleEvent} action.
 */
public record EventScheduleOrchestration(
        String eventName,
        long delaySeconds,
        Map<String, String> fieldMap
) {
}
