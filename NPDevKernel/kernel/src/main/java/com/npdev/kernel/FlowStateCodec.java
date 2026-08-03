package com.npdev.kernel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code _npdev.await} flow-state marshaling keys and helpers, split out of {@link KernelRunner}
 * verbatim -- no behavior change. Used by both the {@code AWAIT_EVENT} step body ({@link
 * AwaitEventStep}) when a flow parks, and by {@link ResumeCoordinator#resolveWaitCriteria} when
 * rehydrating a waiting instance (including after a real process restart -- this is the durable
 * checkpoint shape). This file deliberately stays a flat sibling in {@code com.npdev.kernel}, not a
 * subpackage, for the same reason {@code TableRenamePass}/{@code DesiredSchemaFactory} documented
 * for the 2.B.4 split: several helpers it depends on (e.g. {@link KernelRunner#normalizeRef}) are
 * package-private, and Java sub-packages get no special access to them.
 */
final class FlowStateCodec {

    private FlowStateCodec() {
    }

    static final String AWAIT_STATE_KEY = "_npdev.await";
    static final String AWAIT_FIELD_EVENT_NAME = "awaitEventName";
    static final String AWAIT_FIELD_MATCH_CORRELATION = "matchCorrelation";
    static final String AWAIT_FIELD_PAYLOAD_MATCH_REFS = "payloadMatchRefs";
    static final String AWAIT_FIELD_STEP_INDEX = "stepIndex";
    static final String AWAIT_FIELD_STEP_NAME = "stepName";
    static final String AWAIT_FIELD_AWAIT_REF = "awaitRef";

    /**
     * B15(A) (sequential await-in-loop, see docs/BOUNDARY_LIFT_ROADMAP.md): the marker {@link
     * AwaitEventStep} sets in {@code state} the instant it consumes iteration i's awaited event --
     * BEFORE {@link ForEachStep} advances {@code __forEachProgress.<step>} past i. Deliberately
     * checked/cleared across two DIFFERENT persists (see each class's own javadoc for why one
     * persist isn't enough): a crash between "event consumed" and "outer iteration progress
     * advanced" must resume by finding this marker already durable and skipping straight past a
     * re-query of an event the idempotency store has already marked processed (which would
     * otherwise find nothing and re-park the flow WAITING forever on an event that will never
     * come again) -- not by re-invoking {@code awaitEvent()} a second time.
     */
    static final String FOR_EACH_AWAIT_SATISFIED_KEY_PREFIX = "__forEachAwaitSatisfied.";

    /**
     * B15(A): per-iteration correlation id {@link ForEachStep} writes into {@code state.correlationId}
     * before executing iteration i's body, so an await nested in the loop discriminates iteration
     * i's own reply from any other iteration's (or any other flow instance's) event of the same
     * name -- required, never blank (see {@link #requireNonBlankIterationCorrelationComponent}):
     * {@code KernelRunner.matchesCorrelation} treats a blank correlation as "matches anything", so a
     * silently-blank derivation here would silently let iteration N+1's event satisfy iteration N's
     * await. Derived from {@code executionId} (globally unique per flow instance -- NOT the flow's
     * own business {@code correlationId}, which two different instances may deliberately share for
     * cross-flow linking, and which would collide across instances running the same flow) plus the
     * await step's own name plus the iteration index, so it is both deterministic (a resumed
     * iteration re-derives the identical value) and collision-free.
     */
    static String deriveForEachIterationCorrelationId(String executionId, String awaitStepName, int iterationIndex) {
        if (executionId == null || executionId.isBlank() || awaitStepName == null || awaitStepName.isBlank()) {
            return null;
        }
        return executionId + "::forEach:" + awaitStepName + ":" + iterationIndex;
    }

    static Map<String, Object> buildAwaitState(
            FlowStepDefinition step,
            int stepIndex,
            String awaitRef
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(AWAIT_FIELD_EVENT_NAME, step.getAwaitEventName());
        out.put(AWAIT_FIELD_MATCH_CORRELATION, step.isAwaitMatchCorrelation());
        out.put(AWAIT_FIELD_PAYLOAD_MATCH_REFS, Map.copyOf(step.getAwaitPayloadMatchRefs()));
        out.put(AWAIT_FIELD_STEP_INDEX, Math.max(0, stepIndex));
        out.put(AWAIT_FIELD_STEP_NAME, step.getName());
        out.put(AWAIT_FIELD_AWAIT_REF, normalizeAwaitRef(awaitRef));
        return Map.copyOf(out);
    }

    /**
     * B15(B) (S6, docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)): the per-ITERATION analog of {@link
     * #AWAIT_STATE_KEY} -- a {@code parallelAwait} forEach has up to N outstanding wait
     * descriptors at once (one per not-yet-resolved iteration), so a single flat key cannot
     * represent them. Namespaced by step name (mirroring {@link #FOR_EACH_AWAIT_SATISFIED_KEY_PREFIX})
     * and iteration index. Chosen deliberately over a NEW durable storage surface (a dedicated
     * {@code flow_instance_wait} table/adapter pair, the roadmap's originally-costed Option B): a
     * parallel forEach's N iterations all await the SAME declared event name (only correlation
     * differs per iteration, exactly like B15(A)), so {@code FlowInstanceStore.findWaitingByEvent}
     * already discovers a candidate instance via its EXISTING indexed column with zero schema
     * change -- the only piece a new table would have bought is per-slot correlation
     * discrimination, and {@code state} already round-trips that in full on every checkpoint. This
     * is why B1's migration story is trivial: nothing new was added below the state-blob layer, so
     * there is nothing to migrate for an existing in-flight {@code WAITING_EVENT} row.
     */
    static final String PARALLEL_AWAIT_STATE_KEY_PREFIX = "_npdev.parallelAwait.";

    /** B15(B): companion to {@link #PARALLEL_AWAIT_STATE_KEY_PREFIX} -- set the instant iteration
     *  i's awaited event is durably consumed (mirroring {@link #FOR_EACH_AWAIT_SATISFIED_KEY_PREFIX}'s
     *  own crash-window rationale, now per-iteration instead of per-step: {@code awaitEvent()}
     *  marks a found event PROCESSED in the idempotency store as a side effect of being called at
     *  all, so a crash between "iteration i's event consumed" and "this marker persisted" would
     *  otherwise re-query on resume, find nothing (already marked processed), and leave iteration i
     *  stuck WAITING forever on an event that will never arrive again). {@link
     *  ParallelAwaitForEachStep} checkpoints immediately after setting this for iteration i, before
     *  attempting iteration i+1 -- N separate close-the-window checkpoints, not one. */
    static final String PARALLEL_AWAIT_RESOLVED_KEY_PREFIX = "__parallelAwaitResolved.";

    static String parallelAwaitStateKey(String stepName, int iterationIndex) {
        return PARALLEL_AWAIT_STATE_KEY_PREFIX + stepName + "." + iterationIndex;
    }

    static String parallelAwaitResolvedKey(String stepName, int iterationIndex) {
        return PARALLEL_AWAIT_RESOLVED_KEY_PREFIX + stepName + "." + iterationIndex;
    }

    /** B15(B): per-iteration resolved payload lands at {@code awaitRef + "." + i} -- pluralizing
     *  the single-slot {@code state.put(awaitRef, payload)} convention B15(A) uses, per the
     *  roadmap's own suggested shape. */
    static String parallelAwaitPayloadKey(String awaitRef, int iterationIndex) {
        return normalizeAwaitRef(awaitRef) + "." + iterationIndex;
    }

    /** Shared by the single-slot {@code _npdev.await} reader ({@code ResumeCoordinator.resolveWaitCriteria})
     *  and the multi-slot {@code _npdev.parallelAwait.*} scanner below -- one parser for the SAME
     *  descriptor shape {@link #buildAwaitState} writes, so the two never drift apart. */
    static KernelRunner.WaitCriteria parseWaitCriteria(Map<?, ?> waitMap, String fallbackEventName, int fallbackStepIndex) {
        String eventName = Objects.toString(waitMap.get(AWAIT_FIELD_EVENT_NAME), fallbackEventName);
        boolean matchCorrelation = parseBoolean(waitMap.get(AWAIT_FIELD_MATCH_CORRELATION), true);
        int stepIndex = parseInt(waitMap.get(AWAIT_FIELD_STEP_INDEX), fallbackStepIndex);
        String awaitRef = normalizeAwaitRef(Objects.toString(waitMap.get(AWAIT_FIELD_AWAIT_REF), "awaitedEvent"));
        return new KernelRunner.WaitCriteria(
                eventName, matchCorrelation, parseStringMap(waitMap.get(AWAIT_FIELD_PAYLOAD_MATCH_REFS)), stepIndex, awaitRef);
    }

    /** B15(B): every currently-outstanding (not yet resolved) per-iteration wait slot for a
     *  {@code parallelAwait} forEach, derived entirely from {@code state} -- used by {@link
     *  ResumeCoordinator} to check an incoming event (or a scheduled sweep) against ALL N
     *  outstanding correlations, not just one. Each slot's correlation id is RE-DERIVED (not
     *  stored) via {@link #deriveForEachIterationCorrelationId}, the exact same convention B15(A)
     *  already uses -- reused, not reinvented (B2's own instruction). Returns an empty list for any
     *  instance not in parallel-await mode, so callers fall back to the existing single-slot path
     *  with zero behavior change. */
    static List<ParallelAwaitSlot> resolveOutstandingParallelAwaitSlots(String executionId, Map<String, Object> state) {
        if (executionId == null || executionId.isBlank() || state == null || state.isEmpty()) {
            return List.of();
        }
        List<ParallelAwaitSlot> slots = new ArrayList<>();
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(PARALLEL_AWAIT_STATE_KEY_PREFIX)) {
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> waitMap)) {
                continue;
            }
            String remainder = key.substring(PARALLEL_AWAIT_STATE_KEY_PREFIX.length());
            int lastDot = remainder.lastIndexOf('.');
            if (lastDot <= 0 || lastDot == remainder.length() - 1) {
                continue;
            }
            String stepName = remainder.substring(0, lastDot);
            Integer iterationIndex = tryParsePositiveInt(remainder.substring(lastDot + 1));
            if (iterationIndex == null) {
                continue;
            }
            String correlationId = deriveForEachIterationCorrelationId(executionId, stepName, iterationIndex);
            if (correlationId == null) {
                continue;
            }
            KernelRunner.WaitCriteria criteria = parseWaitCriteria(waitMap, null, -1);
            if (criteria.awaitEventName() == null || criteria.awaitEventName().isBlank()) {
                continue;
            }
            slots.add(new ParallelAwaitSlot(stepName, iterationIndex, correlationId, criteria));
        }
        return List.copyOf(slots);
    }

    private static Integer tryParsePositiveInt(String text) {
        try {
            int value = Integer.parseInt(text);
            return value >= 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** B15(B): one outstanding per-iteration wait slot -- {@code correlationId} is the
     *  DISCRIMINATOR (every slot for the same step shares the same {@code criteria.awaitEventName()},
     *  since they're all the same await step definition; only correlation differs). */
    record ParallelAwaitSlot(String stepName, int iterationIndex, String correlationId, KernelRunner.WaitCriteria criteria) {
    }

    static Map<String, String> parseStringMap(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).trim();
            String value = String.valueOf(entry.getValue()).trim();
            if (key.isBlank() || value.isBlank()) {
                continue;
            }
            out.put(key, value);
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    static boolean parseBoolean(Object rawValue, boolean defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(text);
    }

    static int parseInt(Object rawValue, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    static String normalizeAwaitRef(String awaitRef) {
        String normalized = KernelRunner.normalizeRef(awaitRef);
        return normalized.isBlank() ? "awaitedEvent" : normalized;
    }
}
