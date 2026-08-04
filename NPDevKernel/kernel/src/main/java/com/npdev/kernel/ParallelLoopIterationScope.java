package com.npdev.kernel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Wave 3 (S8_DEFERRED_FIVE_PLAN.md, 2026-08-04): the mechanism half of {@link
 * FlowStateCodec}'s I0 decision record (see the javadoc on {@link
 * FlowStateCodec#PARALLEL_LOOP_SCOPE_KEY_PREFIX} for the full design reasoning -- this class is
 * deliberately just the four operations that record describes, with no independent rationale of
 * its own). Used exclusively by {@link ParallelAwaitForEachStep}.
 *
 * <p>Every method here operates on the SAME flat {@code state} map the durable checkpoint
 * mechanism already persists -- there is no second map, no wrapper view, nothing that could
 * diverge from what a crash-and-resume actually rehydrates.
 */
final class ParallelLoopIterationScope {

    private ParallelLoopIterationScope() {
    }

    /** Captures the pre-loop snapshot exactly once, ever, for this step -- idempotent, a later
     *  call once the key exists is a no-op. Must run BEFORE the first iteration's {@link #enter}. */
    static void captureBaselineOnce(Map<String, Object> state, String stepName) {
        String baselineKey = FlowStateCodec.parallelLoopBaselineKey(stepName);
        if (!state.containsKey(baselineKey)) {
            state.put(baselineKey, new LinkedHashMap<>(state));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> baseline(Map<String, Object> state, String stepName) {
        Object raw = state.get(FlowStateCodec.parallelLoopBaselineKey(stepName));
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** True if bare {@code state} currently holds iteration i's own not-yet-relocated working set
     *  -- a crash between some nested step's own checkpoint and this class's {@link #exit} leaves
     *  exactly this signature, since iterations are attempted strictly sequentially, never
     *  interleaved, and {@link #enter} always durably records the active index before delegating. */
    static boolean isActiveIteration(Map<String, Object> state, String stepName, int iterationIndex) {
        Object active = state.get(FlowStateCodec.parallelLoopActiveIterationKey(stepName));
        return active instanceof Number number && number.intValue() == iterationIndex;
    }

    /** Opens iteration i's bare-state window: overlays its own previously-relocated shadow keys
     *  (from an earlier pass that left it WAITING) back onto the bare keys the loop body reads, and
     *  durably marks it the active iteration. A no-op if bare state already belongs to iteration i
     *  (see {@link #isActiveIteration}) -- overlaying stale shadow values on top of crash-live
     *  in-progress bare state would discard progress a crash left mid-flight. */
    static void enter(Map<String, Object> state, String stepName, int iterationIndex) {
        if (isActiveIteration(state, stepName, iterationIndex)) {
            return;
        }
        String scopePrefix = FlowStateCodec.parallelLoopScopePrefix(stepName, iterationIndex);
        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(state).entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith(scopePrefix)) {
                state.put(key.substring(scopePrefix.length()), entry.getValue());
            }
        }
        state.put(FlowStateCodec.parallelLoopActiveIterationKey(stepName), iterationIndex);
    }

    /** Closes iteration i's bare-state window: every bare key whose CURRENT value differs from (or
     *  is absent from) the pre-loop baseline is relocated into iteration i's own namespace and then
     *  reset to its baseline value (or removed if the baseline never had it); any of iteration i's
     *  PREVIOUSLY relocated keys that no longer differ from baseline (a step explicitly put a key
     *  back to its original value, e.g. {@link AwaitEventStep}'s own {@code state.remove} of the
     *  singleton await-state key once resolved) are dropped from its namespace as stale. Safe to
     *  call whether the iteration fully completed, is still {@code WAITING}, or ended via a nested
     *  {@code return} -- all three leave bare state in a state this diff handles identically. */
    static void exit(Map<String, Object> state, String stepName, int iterationIndex) {
        Map<String, Object> baseline = baseline(state, stepName);
        String scopePrefix = FlowStateCodec.parallelLoopScopePrefix(stepName, iterationIndex);

        Set<String> previouslyScoped = new LinkedHashSet<>();
        for (String key : state.keySet()) {
            if (key != null && key.startsWith(scopePrefix)) {
                previouslyScoped.add(key.substring(scopePrefix.length()));
            }
        }

        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(state).entrySet()) {
            String key = entry.getKey();
            if (key == null || FlowStateCodec.isParallelLoopReservedKey(key)) {
                continue;
            }
            Object currentValue = entry.getValue();
            boolean changed = !baseline.containsKey(key) || !Objects.equals(currentValue, baseline.get(key));
            if (!changed) {
                continue;
            }
            state.put(scopePrefix + key, currentValue);
            previouslyScoped.remove(key);
            if (baseline.containsKey(key)) {
                state.put(key, baseline.get(key));
            } else {
                state.remove(key);
            }
        }

        for (String staleKey : previouslyScoped) {
            state.remove(scopePrefix + staleKey);
        }
        state.remove(FlowStateCodec.parallelLoopActiveIterationKey(stepName));
    }

    /** Reads iteration i's relocated value of {@code innerKey} (e.g. the resolved await payload)
     *  without opening its scope -- used for the post-loop combined-payload collection, which must
     *  run after every iteration's {@link #exit} but before {@link #clearAll}. */
    static Object readScoped(Map<String, Object> state, String stepName, int iterationIndex, String innerKey) {
        return state.get(FlowStateCodec.parallelLoopScopedKey(stepName, iterationIndex, innerKey));
    }

    /** Final cleanup once every iteration has durably completed -- removes the baseline snapshot,
     *  the active-iteration marker, and every remaining per-iteration shadow/done key for this
     *  step, so nothing about it lingers in {@code state} once the step itself is done. */
    static void clearAll(Map<String, Object> state, String stepName) {
        state.remove(FlowStateCodec.parallelLoopBaselineKey(stepName));
        state.remove(FlowStateCodec.parallelLoopActiveIterationKey(stepName));
        String scopePrefix = FlowStateCodec.PARALLEL_LOOP_SCOPE_KEY_PREFIX + stepName + ".";
        String donePrefix = FlowStateCodec.PARALLEL_LOOP_DONE_KEY_PREFIX + stepName + ".";
        state.keySet().removeIf(key -> key != null && (key.startsWith(scopePrefix) || key.startsWith(donePrefix)));
    }
}
