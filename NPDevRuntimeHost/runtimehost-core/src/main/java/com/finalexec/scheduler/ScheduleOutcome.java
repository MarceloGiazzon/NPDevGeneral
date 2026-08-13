package com.finalexec.scheduler;

import java.time.Instant;

/** LNCH-12: the last-known outcome of a declared flow schedule, for ControlPanel's "last outcome" list. */
public record ScheduleOutcome(
        String flowName,
        String tenantId,
        String cron,
        Instant lastRunAt,
        String status,
        String lastError,
        long runCount
) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";

    public static ScheduleOutcome pending(String flowName, String tenantId, String cron) {
        return new ScheduleOutcome(flowName, tenantId, cron, null, STATUS_PENDING, null, 0);
    }

    public ScheduleOutcome withSuccess(Instant runAt) {
        return new ScheduleOutcome(flowName, tenantId, cron, runAt, STATUS_SUCCESS, null, runCount + 1);
    }

    public ScheduleOutcome withFailure(Instant runAt, String error) {
        return new ScheduleOutcome(flowName, tenantId, cron, runAt, STATUS_FAILURE, error, runCount + 1);
    }
}
