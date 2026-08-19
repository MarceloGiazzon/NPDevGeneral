package com.npdev.adapters.resumebootstrap.spring;

import com.npdev.kernel.ports.MetricsSink;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.Objects;

/**
 * Continuous drain of the durable scheduled-event table:
 * periodically fires every {@code npdev_scheduled_event} row whose {@code due_at} has passed.
 *
 * <p><b>Why this exists.</b> The whole substrate was already built -- the table
 * ({@code NpdevScheduledEventTable}), its {@code due_at} column, and a multi-instance-safe
 * compare-and-set claim -- but measured 2026-08-18, the only production caller of
 * {@code processDueScheduledEvents} in the repo was {@code RuntimeSchedulesController}'s
 * {@code POST /api/runtime/schedules/process-due}. Nothing polled it. So a delayed event in an
 * unattended app stayed PENDING forever and fired only when a human poked REST -- the table was
 * durable and correct and never drained.
 *
 * <p><b>Why {@code forceDue} is pinned to FALSE.</b> The REST endpoint takes it as a parameter
 * because an operator debugging a stuck app may legitimately want to fire everything now. A timer
 * must not: {@code forceDue=true} drops the {@code due_at <= ?} predicate entirely, which would make
 * every delay in the model fire on the next tick and turn a scheduled event into an immediate one.
 *
 * <p><b>Why it does not touch {@link com.npdev.kernel.runtime.SchedulerRuntimeState}.</b> That state
 * is read by {@code NpdevSchedulerHealthIndicator} as the RESUME sweep's liveness -- one
 * {@code lastOutcome}, one {@code lastTickAtEpochMs}. A second writer would make "which sweep
 * failed?" unanswerable from the health payload. This runner reports through {@link MetricsSink}
 * under its own {@code schedule_drain} names instead.
 *
 * <p>Shares the {@link ResumeSchedulerRunner} tick property and Spring's single-threaded default
 * task scheduler with the resume sweep: both are the scheduler subsystem, both are bounded by
 * {@code npdev.scheduler.batch-limit}, and giving the drain its own cadence knob would add a
 * property with no operator asking for it.
 */
public final class ScheduledEventDrainRunner {
    private final ScheduledEventDrain drain;
    private final int drainLimit;
    private final boolean schedulerEnabled;
    private final MetricsSink metricsSink;

    public ScheduledEventDrainRunner(
            ScheduledEventDrain drain,
            int drainLimit,
            boolean schedulerEnabled
    ) {
        this(drain, drainLimit, schedulerEnabled, MetricsSink.noop());
    }

    public ScheduledEventDrainRunner(
            ScheduledEventDrain drain,
            int drainLimit,
            boolean schedulerEnabled,
            MetricsSink metricsSink
    ) {
        this.drain = Objects.requireNonNull(drain, "drain");
        this.drainLimit = drainLimit <= 0 ? 1000 : drainLimit;
        this.schedulerEnabled = schedulerEnabled;
        this.metricsSink = metricsSink == null ? MetricsSink.noop() : metricsSink;
    }

    @Scheduled(fixedDelayString = "${npdev.scheduler.tick-millis:${npdev.resume.pollMs:2000}}")
    public void poll() {
        long now = System.currentTimeMillis();
        metricsSink.gauge("npdev.scheduler.schedule_drain_last_tick_ms", now, Map.of());
        if (!schedulerEnabled) {
            metricsSink.inc("npdev.scheduler.schedule_drain_tick", Map.of("outcome", "SKIPPED"));
            return;
        }
        try {
            Map<String, Object> summary = drain.processDue(Boolean.FALSE, drainLimit);
            metricsSink.inc("npdev.scheduler.schedule_drain_tick", Map.of("outcome", "SUCCESS"));
            metricsSink.gauge("npdev.scheduler.schedule_drain_processed_count", countOf(summary, "processed"), Map.of());
            metricsSink.gauge("npdev.scheduler.schedule_drain_failed_count", countOf(summary, "failed"), Map.of());
        } catch (RuntimeException ex) {
            metricsSink.inc("npdev.scheduler.schedule_drain_tick", Map.of("outcome", "FAILED"));
            metricsSink.gauge("npdev.scheduler.schedule_drain_failed_count", 1L,
                    Map.of("errorCode", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private static long countOf(Map<String, Object> summary, String key) {
        if (summary == null) {
            return 0L;
        }
        Object value = summary.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * The single call this runner makes, as a seam rather than a type dependency.
     *
     * <p>{@code processDueScheduledEvents} lives on {@code GeneratedCrudRuntimeSupport} in
     * {@code :adapters:expression-cel}. This adapter depends only on {@code :kernel}; binding the
     * method reference in the RuntimeHost (which already has both on its classpath) keeps it that
     * way instead of making one adapter depend on a sibling adapter for one method.
     */
    @FunctionalInterface
    public interface ScheduledEventDrain {
        Map<String, Object> processDue(Boolean forceDue, Integer limit);
    }
}
