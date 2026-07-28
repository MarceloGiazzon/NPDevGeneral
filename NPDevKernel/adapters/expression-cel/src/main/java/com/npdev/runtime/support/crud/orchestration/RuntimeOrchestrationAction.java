package com.npdev.runtime.support.crud.orchestration;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): one action within a
 * {@link RuntimeOrchestration}, with at most one of {@link #createAction}, {@link #capabilityAction}
 * or {@link #scheduleAction} populated depending on {@link #type}.
 */
public record RuntimeOrchestrationAction(
        int index,
        String type,
        EventCreateOrchestration createAction,
        EventCapabilityOrchestration capabilityAction,
        EventScheduleOrchestration scheduleAction
) {
}
