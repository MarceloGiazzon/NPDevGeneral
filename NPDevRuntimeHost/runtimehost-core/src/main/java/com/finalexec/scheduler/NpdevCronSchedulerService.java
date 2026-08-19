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
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
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
 *
 * <p><b>R2.7:</b> scale this service to two replicas against one database and, without the claim
 * below, EVERY tick double-fires -- each JVM registers its own independent {@link CronTrigger} and
 * calls {@link KernelRunner#execute} with no coordination whatsoever. {@link CronFireClaimStore}
 * closes that gap by reusing RUN-2's proven claim mechanism: before running, this service computes
 * a deterministic {@code scheduledFireTime} for the tick (via {@link #advanceFireTime}, calendar
 * math off the SAME cron expression -- never observed wall-clock "now", which would let scheduler
 * jitter split one logical tick into two different claim keys) and claims exclusive firing rights
 * for (flowName, tenantId, scheduledFireTime) before calling {@link KernelRunner#execute}. Losing
 * the claim means another instance already has this tick -- fall through without running.</p>
 */
@Component
@ConditionalOnProperty(name = "npdev.cronScheduler.enabled", havingValue = "true", matchIfMissing = true)
public class NpdevCronSchedulerService {

    private static final Logger LOG = Logger.getLogger(NpdevCronSchedulerService.class.getName());
    private static final String DEFAULT_TENANT_ID = "default";

    /** One per JVM, mirroring {@code ResumeCoordinator.RESUMER_ID} exactly. */
    private static final String CLAIMANT_ID = UUID.randomUUID().toString();

    private final CompiledModel compiledModel;
    private final KernelRunner kernelRunner;
    private final ScheduleOutcomeTracker tracker;
    private final int poolSize;
    private final CronFireClaimStore claimStore;

    private ThreadPoolTaskScheduler taskScheduler;
    private final List<ScheduledFuture<?>> scheduledFutures = new ArrayList<>();

    /**
     * R2.7: the deterministic fire-time chain for each (flow, tenant) schedule, keyed by
     * {@link #scheduleKey}. Seeded at registration to "now" and advanced ONLY via
     * {@link CronExpression#next}, so the sequence tracks purely off the cron's own calendar grid --
     * see {@link #advanceFireTime}.
     */
    private final Map<String, CronExpression> cronExpressionsByKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Instant>> fireAnchorByKey = new ConcurrentHashMap<>();

    public NpdevCronSchedulerService(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            ScheduleOutcomeTracker tracker,
            @Value("${npdev.cronScheduler.poolSize:4}") int poolSize,
            DataSource dataSource
    ) {
        this.compiledModel = compiledModel;
        this.kernelRunner = kernelRunner;
        this.tracker = tracker;
        this.poolSize = poolSize;
        this.claimStore = new CronFireClaimStore(dataSource);
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
            CronExpression cronExpression;
            try {
                trigger = new CronTrigger(schedule.getCron());
                cronExpression = CronExpression.parse(schedule.getCron());
            } catch (IllegalArgumentException malformedCron) {
                LOG.log(Level.SEVERE, "Flow " + flow.getName() + " has an invalid schedule.cron '"
                        + schedule.getCron() + "' -- not registered", malformedCron);
                continue;
            }
            for (String tenantId : tenants) {
                String normalizedTenant = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT_ID : tenantId.trim();
                String key = scheduleKey(flow.getName(), normalizedTenant);
                cronExpressionsByKey.put(key, cronExpression);
                fireAnchorByKey.put(key, new AtomicReference<>(Instant.now()));
                tracker.registerPending(flow.getName(), normalizedTenant, schedule.getCron());
                ScheduledFuture<?> future = taskScheduler.schedule(
                        () -> runScheduledFlow(flow.getName(), normalizedTenant, key), trigger);
                scheduledFutures.add(future);
            }
        }
    }

    private static String scheduleKey(String flowName, String tenantId) {
        return flowName + "::" + tenantId;
    }

    /**
     * R2.7: the deterministic scheduledFireTime for THIS invocation, computed off the cron's own
     * calendar grid rather than observed wall-clock "now" -- {@link CronExpression#next} always
     * returns the earliest match strictly after the given instant, so a chain that starts at
     * registration and is advanced ONLY by feeding each result back in as the next anchor lands on
     * the SAME sequence of absolute instants regardless of which JVM computes it or how much
     * scheduler-thread jitter delayed the actual callback -- two instances registering within the
     * same cron granularity converge on identical claim keys. {@code updateAndGet} makes the
     * read-compute-write atomic, so two overlapping invocations for the same key (a slow run still
     * executing when the next tick fires) can never both advance from the same anchor.
     *
     * @return null only if the cron expression has no further matches (a fixed-date expression whose
     *         date has passed) or if this key was never registered -- callers must not block firing
     *         on that, since it means "no coordinated claim was possible", not "someone else has it".
     */
    private Instant advanceFireTime(String key) {
        CronExpression cronExpression = cronExpressionsByKey.get(key);
        AtomicReference<Instant> anchor = fireAnchorByKey.get(key);
        if (cronExpression == null || anchor == null) {
            return null;
        }
        return anchor.updateAndGet(previous -> {
            ZonedDateTime next = cronExpression.next(previous.atZone(ZoneId.systemDefault()));
            return next == null ? previous : next.toInstant();
        });
    }

    private void runScheduledFlow(String flowName, String tenantId, String scheduleKey) {
        Instant scheduledFireTime = advanceFireTime(scheduleKey);
        if (scheduledFireTime != null
                && !claimStore.tryClaim(flowName, tenantId, scheduledFireTime, CLAIMANT_ID, 0L)) {
            LOG.fine(() -> "Scheduled flow " + flowName + " (tenant " + tenantId + ") skipped at "
                    + scheduledFireTime + " -- another instance already claimed this fire window");
            return;
        }
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
