package com.npdev.kernel.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FLOW-1: merge-on-save for execution traces.
 *
 * <p>The kernel builds a FRESH step-trace list for every {@code executeFlowInstance} call (a first
 * run, a resume after a WAITING step, an operator retry), so a trace store that simply overwrote
 * by executionId kept only the LAST segment: a resumed flow lost the steps it ran before parking
 * and its recorded start was the resume time, which made "how long did this flow wait for a human"
 * unanswerable from the trace.
 *
 * <p>This merges a newly finished segment ({@code next}) into the trace already stored for the
 * same execution ({@code prior}):
 *
 * <ul>
 *   <li>The segment declares where it started via {@code meta.tags["startStepIndex"]} and whether
 *       it continues an earlier segment via {@code meta.tags["resumed"]} (the kernel always sets the
 *       first, and the second on a resume or an operator retry). Prior steps with {@code stepIndex < start} are kept; prior steps at or
 *       after it are re-run by this segment and are replaced by the segment's own steps.</li>
 *   <li>A replaced step that was parked ({@code awaitedEventStatus = WAITING}) hands its park time
 *       to the replacing step as {@code waitStartedAtEpochMs}; once the replacing step is not itself
 *       parked, {@code waitedMs = step.startedAt - waitStartedAt} is recorded -- the human/event
 *       wait. A quiet resume (the sweep re-checks the await and parks again) therefore keeps the
 *       ORIGINAL park time instead of restarting the clock.</li>
 *   <li>A replaced step that was NOT parked (operator retry of a failed step) bumps
 *       {@code stepAttempts} on the replacing step, so the retry is visible and steps are not
 *       duplicated.</li>
 *   <li>The merged trace starts when the FIRST segment started and ends when the LAST one ended;
 *       its outcome is the last segment's. {@code meta.tags["segments"]} counts the segments.</li>
 * </ul>
 *
 * <p>A segment that is neither {@code resumed} nor starts past step 0 is "a whole new run": the new
 * trace simply replaces the old one (the behaviour every store had before this class existed).
 */
public final class FlowTraceMerge {

    public static final String START_STEP_INDEX_TAG = "startStepIndex";
    public static final String RESUMED_TAG = "resumed";
    public static final String SEGMENTS_TAG = "segments";
    public static final String WAIT_STARTED_AT_INFO = "waitStartedAtEpochMs";
    public static final String WAITED_MS_INFO = "waitedMs";
    public static final String STEP_ATTEMPTS_INFO = "stepAttempts";
    public static final String AWAIT_STATUS_INFO = "awaitedEventStatus";
    public static final String AWAIT_STATUS_WAITING = "WAITING";

    private FlowTraceMerge() {
    }

    public static FlowTrace merge(FlowTrace prior, FlowTrace next) {
        if (next == null) {
            return prior;
        }
        if (prior == null || prior.meta() == null) {
            return next;
        }
        int start = startStepIndex(next);
        if (start <= 0 && !Boolean.TRUE.equals(next.meta().tags().get(RESUMED_TAG))) {
            return next;
        }

        if (alreadyMerged(prior, next)) {
            return prior;
        }

        List<StepTrace> merged = new ArrayList<>();
        Map<Integer, StepTrace> replaced = new LinkedHashMap<>();
        for (StepTrace step : prior.steps()) {
            if (step.stepIndex() < start) {
                merged.add(step);
            } else {
                // Two prior entries sharing an index cannot happen for one segment, but a stale
                // double-merge could; keep the earliest as the one the new step supersedes.
                replaced.putIfAbsent(step.stepIndex(), step);
            }
        }
        for (StepTrace step : next.steps()) {
            StepTrace previous = replaced.get(step.stepIndex());
            merged.add(previous == null || !previous.stepName().equals(step.stepName())
                    ? step
                    : carryOver(previous, step));
        }

        Map<String, Object> tags = new LinkedHashMap<>(next.meta().tags());
        tags.put(SEGMENTS_TAG, segments(prior) + 1);
        FlowTraceMeta meta = new FlowTraceMeta(
                next.meta().executionId(),
                next.meta().correlationId(),
                next.meta().flowName(),
                next.meta().tenantId(),
                next.meta().actorId(),
                tags
        );
        long startedAt = Math.min(prior.startedAtEpochMs(), next.startedAtEpochMs());
        long endedAt = Math.max(next.endedAtEpochMs(), startedAt);
        return new FlowTrace(meta, startedAt, endedAt, next.outcome(), merged);
    }

    /** True when the step is a parked await (the flow was waiting on it when the segment ended). */
    public static boolean isParked(StepTrace step) {
        return step != null && AWAIT_STATUS_WAITING.equals(String.valueOf(step.info().get(AWAIT_STATUS_INFO)));
    }

    /**
     * Saving the same finished segment twice (a tracer chain that reaches one store on two paths)
     * must not look like a retry of its own steps: the segment's first step is already stored with
     * the very same timestamps.
     */
    private static boolean alreadyMerged(FlowTrace prior, FlowTrace next) {
        if (next.steps().isEmpty()) {
            return prior.endedAtEpochMs() == next.endedAtEpochMs() && prior.outcome() == next.outcome();
        }
        StepTrace first = next.steps().get(0);
        for (StepTrace step : prior.steps()) {
            if (step.stepIndex() == first.stepIndex()
                    && step.stepName().equals(first.stepName())
                    && step.startedAtEpochMs() == first.startedAtEpochMs()
                    && step.endedAtEpochMs() == first.endedAtEpochMs()) {
                return true;
            }
        }
        return false;
    }

    private static StepTrace carryOver(StepTrace previous, StepTrace replacement) {
        Map<String, Object> info = new LinkedHashMap<>(replacement.info());
        if (isParked(previous)) {
            long waitStart = number(previous.info().get(WAIT_STARTED_AT_INFO), previous.endedAtEpochMs());
            info.put(WAIT_STARTED_AT_INFO, waitStart);
            if (!isParked(replacement)) {
                info.put(WAITED_MS_INFO, Math.max(0L, replacement.startedAtEpochMs() - waitStart));
            }
        } else {
            info.put(STEP_ATTEMPTS_INFO, number(previous.info().get(STEP_ATTEMPTS_INFO), 1L) + 1L);
        }
        return new StepTrace(
                replacement.stepIndex(),
                replacement.stepName(),
                replacement.stepType(),
                replacement.startedAtEpochMs(),
                replacement.endedAtEpochMs(),
                replacement.outcome(),
                info,
                replacement.invariantViolations(),
                replacement.capabilityError()
        );
    }

    private static int startStepIndex(FlowTrace trace) {
        Object value = trace.meta().tags().get(START_STEP_INDEX_TAG);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long segments(FlowTrace trace) {
        return number(trace.meta().tags().get(SEGMENTS_TAG), 1L);
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
