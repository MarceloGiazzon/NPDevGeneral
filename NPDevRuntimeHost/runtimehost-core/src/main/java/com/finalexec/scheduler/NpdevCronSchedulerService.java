package com.finalexec.scheduler;

import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowSchedule;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import com.npdev.kernel.KernelRunner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LNCH-12: runs every flow that declares a {@code schedule} (cron + tenant scope), one
 * {@link CronTrigger} registration per (flow, tenant) pair, invoking {@link KernelRunner#execute}
 * exactly the way an HTTP-triggered run would -- same authorization (a system principal, see
 * {@link ExecutionContext#system}, never the ControlPanel superuser key), same event emission, so a
 * scheduled run is indistinguishable from an invoked one in the event store except for its actorId.
 *
 * <p>v1 missed-window policy: skip, don't catch up. This falls out of {@link CronTrigger}'s own
 * semantics for free -- it always computes the next fire time from "now", it never queues up
 * missed firings from while the app was down. That is deliberate (see
 * {@code docs/LAUNCH_READINESS_GAPS.md#LNCH-12}), not an oversight: inventing catch-up semantics
 * is exactly the kind of undocumented behavior that corrupts data quietly.</p>
 */
@Component
@ConditionalOnProperty(name = "npdev.cronScheduler.enabled", havingValue = "true", matchIfMissing = true)
public class NpdevCronSchedulerService {

    private static final Logger LOG = Logger.getLogger(NpdevCronSchedulerService.class.getName());
    private static final String DEFAULT_TENANT_ID = "default";

    private final CompiledModel compiledModel;
    private final KernelRunner kernelRunner;
    private final ScheduleOutcomeTracker tracker;
    private final int poolSize;

    private ThreadPoolTaskScheduler taskScheduler;
    private final List<ScheduledFuture<?>> scheduledFutures = new ArrayList<>();

    public NpdevCronSchedulerService(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            ScheduleOutcomeTracker tracker,
            @Value("${npdev.cronScheduler.poolSize:4}") int poolSize
    ) {
        this.compiledModel = compiledModel;
        this.kernelRunner = kernelRunner;
        this.tracker = tracker;
        this.poolSize = poolSize;
    }

    @PostConstruct
    void start() {
        List<CompiledFlow> scheduledFlows = compiledModel.getFlows().stream()
                .filter(flow -> flow.getSchedule() != null)
                .toList();
        if (scheduledFlows.isEmpty()) {
            return;
        }

        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(Math.max(1, poolSize));
        taskScheduler.setThreadNamePrefix("npdev-cron-");
        taskScheduler.initialize();

        for (CompiledFlow flow : scheduledFlows) {
            CompiledFlowSchedule schedule = flow.getSchedule();
            List<String> tenants = schedule.getTenantScope().isEmpty()
                    ? List.of(DEFAULT_TENANT_ID)
                    : schedule.getTenantScope();
            CronTrigger trigger;
            try {
                trigger = new CronTrigger(schedule.getCron());
            } catch (IllegalArgumentException malformedCron) {
                LOG.log(Level.SEVERE, "Flow " + flow.getName() + " has an invalid schedule.cron '"
                        + schedule.getCron() + "' -- not registered", malformedCron);
                continue;
            }
            for (String tenantId : tenants) {
                String normalizedTenant = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT_ID : tenantId.trim();
                tracker.registerPending(flow.getName(), normalizedTenant, schedule.getCron());
                ScheduledFuture<?> future = taskScheduler.schedule(
                        () -> runScheduledFlow(flow.getName(), normalizedTenant), trigger);
                scheduledFutures.add(future);
            }
        }
    }

    private void runScheduledFlow(String flowName, String tenantId) {
        ExecutionContext context = ExecutionContext.system(tenantId);
        try {
            ExecutionResult result = kernelRunner.execute(flowName, Map.of(), context);
            if (result != null && result.getStatus() == ExecutionStatus.OK) {
                tracker.recordSuccess(flowName, tenantId);
            } else {
                String error = result == null ? "null_result" : result.getStatus() + " " + result.getError();
                LOG.warning("Scheduled flow " + flowName + " (tenant " + tenantId + ") did not complete successfully: " + error);
                tracker.recordFailure(flowName, tenantId, error);
            }
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Scheduled flow " + flowName + " (tenant " + tenantId + ") threw", exception);
            tracker.recordFailure(flowName, tenantId, String.valueOf(exception.getMessage()));
        }
    }

    @PreDestroy
    void stop() {
        for (ScheduledFuture<?> future : scheduledFutures) {
            future.cancel(false);
        }
        scheduledFutures.clear();
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }
}
