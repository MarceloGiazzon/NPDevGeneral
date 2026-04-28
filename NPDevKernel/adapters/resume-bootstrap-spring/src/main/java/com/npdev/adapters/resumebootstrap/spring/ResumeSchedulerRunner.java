package com.npdev.adapters.resumebootstrap.spring;

import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.MetricsSink;
import com.npdev.kernel.runtime.SchedulerRuntimeState;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.Objects;

/**
 * Continuous recovery loop:
 * periodically asks kernel to scan waiting executions and resume those with available events.
 */
public final class ResumeSchedulerRunner {
    private final KernelRunner kernelRunner;
    private final int resumeLimit;
    private final boolean schedulerEnabled;
    private final SchedulerRuntimeState runtimeState;
    private final MetricsSink metricsSink;

    public ResumeSchedulerRunner(
            KernelRunner kernelRunner,
            int resumeLimit,
            boolean schedulerEnabled,
            SchedulerRuntimeState runtimeState
    ) {
        this(kernelRunner, resumeLimit, schedulerEnabled, runtimeState, MetricsSink.noop());
    }

    public ResumeSchedulerRunner(
            KernelRunner kernelRunner,
            int resumeLimit,
            boolean schedulerEnabled,
            SchedulerRuntimeState runtimeState,
            MetricsSink metricsSink
    ) {
        this.kernelRunner = Objects.requireNonNull(kernelRunner, "kernelRunner");
        this.resumeLimit = resumeLimit <= 0 ? 1000 : resumeLimit;
        this.schedulerEnabled = schedulerEnabled;
        this.runtimeState = runtimeState == null ? new SchedulerRuntimeState() : runtimeState;
        this.metricsSink = metricsSink == null ? MetricsSink.noop() : metricsSink;
    }

    @Scheduled(fixedDelayString = "${npdev.scheduler.tick-millis:${npdev.resume.pollMs:2000}}")
    public void poll() {
        long now = System.currentTimeMillis();
        metricsSink.gauge("npdev.scheduler.last_tick_ms", now, Map.of());
        if (!schedulerEnabled) {
            runtimeState.markSkipped(now, "disabled");
            metricsSink.inc("npdev.scheduler.tick", Map.of("outcome", "SKIPPED"));
            return;
        }
        try {
            int resumedCount = kernelRunner.resumeAllWaitingExecutions(resumeLimit);
            runtimeState.markSuccess(now);
            metricsSink.inc("npdev.scheduler.tick", Map.of("outcome", "SUCCESS"));
            metricsSink.gauge("npdev.scheduler.resume_success_count", resumedCount, Map.of());
            metricsSink.gauge("npdev.scheduler.resume_failure_count", 0L, Map.of());
            metricsSink.gauge("npdev.scheduler.waiting_eligible_count", Math.max(0L, resumedCount), Map.of());
        } catch (RuntimeException ex) {
            runtimeState.markFailure(now, ex);
            metricsSink.inc("npdev.scheduler.tick", Map.of("outcome", "FAILED"));
            metricsSink.gauge("npdev.scheduler.resume_failure_count", 1L, Map.of("errorCode", ex.getClass().getSimpleName()));
            throw ex;
        }
    }
}