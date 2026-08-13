package com.finalexec.scheduler;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LNCH-12: in-memory record of each declared schedule's last outcome -- deliberately not durable
 * across restarts (v1; the flow's own normal event emission is the durable record of what actually
 * ran, this is just a fast summary for ControlPanel). Shared between {@link NpdevCronSchedulerService}
 * (writer) and the ControlPanel schedules endpoint (reader).
 */
@Component
public class ScheduleOutcomeTracker {

    private final Map<String, ScheduleOutcome> outcomesByKey = new ConcurrentHashMap<>();

    public static String key(String flowName, String tenantId) {
        return flowName + "@" + tenantId;
    }

    public void registerPending(String flowName, String tenantId, String cron) {
        outcomesByKey.putIfAbsent(key(flowName, tenantId), ScheduleOutcome.pending(flowName, tenantId, cron));
    }

    public void recordSuccess(String flowName, String tenantId) {
        outcomesByKey.compute(key(flowName, tenantId), (k, existing) -> {
            ScheduleOutcome base = existing == null ? ScheduleOutcome.pending(flowName, tenantId, "") : existing;
            return base.withSuccess(Instant.now());
        });
    }

    public void recordFailure(String flowName, String tenantId, String error) {
        outcomesByKey.compute(key(flowName, tenantId), (k, existing) -> {
            ScheduleOutcome base = existing == null ? ScheduleOutcome.pending(flowName, tenantId, "") : existing;
            return base.withFailure(Instant.now(), error);
        });
    }

    public List<ScheduleOutcome> all() {
        return List.copyOf(outcomesByKey.values());
    }
}
