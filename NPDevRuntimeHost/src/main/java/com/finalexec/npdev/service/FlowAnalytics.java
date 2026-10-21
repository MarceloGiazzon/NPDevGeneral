package com.finalexec.npdev.service;

import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMerge;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FLOW-1: flow analytics computed in Java over already tenant-scoped, already redacted
 * {@link FlowTrace}s. No SQL, no new store: the traces ARE the data (see {@link FlowTraceMerge} for
 * why a resumed flow's trace now carries every step and the human wait).
 *
 * <p>Definitions, kept here because every number the endpoint returns is one of these:
 * <ul>
 *   <li><b>outcome</b> of an execution: {@code OK}; {@code WAITING} when its trace still ends on a
 *       parked await; otherwise {@code FAILED}.</li>
 *   <li><b>serviceMs</b> of a step entry: {@code endedAt - startedAt}. A parked step (the flow is
 *       still waiting on it) is a live count, never a duration sample.</li>
 *   <li><b>waitMs</b> of a step entry: {@code info.waitedMs}, written when a parked await resumed
 *       (the time between park and resume -- a human task not yet done, an event not yet arrived).</li>
 *   <li><b>retried</b>: step entries whose {@code info.stepAttempts} is above 1 (an operator retry
 *       replaced the failed attempt; see {@link FlowTraceMerge}).</li>
 *   <li><b>elapsedMs</b> of a step entry: {@code serviceMs + waitMs}. Bottlenecks are ranked by the
 *       MEDIAN elapsedMs (ties: p95, then name) -- "which step does a typical execution lose the
 *       most time in", waits included.</li>
 *   <li><b>median</b>: middle sample, or the mean of the two middle samples for an even count.
 *       <b>p95</b>: nearest rank, {@code sorted[ceil(0.95 * n) - 1]}.</li>
 *   <li><b>execution durationMs</b>: {@code endedAt - startedAt} of the merged trace (so it spans
 *       waits), over OK and FAILED executions only -- a WAITING one has no end yet.</li>
 * </ul>
 */
final class FlowAnalytics {

    private static final int BOTTLENECK_LIMIT = 10;

    private FlowAnalytics() {
    }

    static Map<String, Object> compute(List<FlowTrace> traces) {
        Map<String, List<FlowTrace>> byFlow = new TreeMap<>();
        Map<String, Long> overall = newOutcomeCounts();
        for (FlowTrace trace : traces) {
            byFlow.computeIfAbsent(trace.meta().flowName(), key -> new ArrayList<>()).add(trace);
            overall.merge(outcomeOf(trace), 1L, Long::sum);
        }

        List<Map<String, Object>> flows = new ArrayList<>();
        List<Map<String, Object>> bottlenecks = new ArrayList<>();
        List<Long> allWaits = new ArrayList<>();
        long currentlyWaiting = 0;
        for (Map.Entry<String, List<FlowTrace>> entry : byFlow.entrySet()) {
            FlowStats stats = flowStats(entry.getKey(), entry.getValue());
            flows.add(stats.json);
            for (Map<String, Object> step : stats.rankedSteps) {
                Map<String, Object> ranked = new LinkedHashMap<>();
                ranked.put("flowName", entry.getKey());
                ranked.putAll(step);
                bottlenecks.add(ranked);
            }
            allWaits.addAll(stats.waits);
            currentlyWaiting += stats.currentlyWaiting;
        }
        bottlenecks.sort(rankOrder().thenComparing(row -> String.valueOf(row.get("flowName"))));
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < Math.min(BOTTLENECK_LIMIT, bottlenecks.size()); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.putAll(bottlenecks.get(i));

            top.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executionCount", traces.size());
        out.put("byOutcome", overall);
        Map<String, Object> waiting = new LinkedHashMap<>();
        waiting.put("currentlyWaitingExecutions", currentlyWaiting);
        waiting.put("resumedWaits", summary(allWaits));
        out.put("humanWait", waiting);
        out.put("flows", flows);
        out.put("bottlenecks", top);
        return out;
    }

    private static final class FlowStats {
        Map<String, Object> json;
        List<Map<String, Object>> rankedSteps = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        long currentlyWaiting;
    }

    private static final class StepAcc {
        String name;
        String type;
        int minIndex = Integer.MAX_VALUE;
        long reached;
        long ok;
        long failed;
        long parked;
        long retried;
        final List<Long> service = new ArrayList<>();
        final List<Long> wait = new ArrayList<>();
        final List<Long> elapsed = new ArrayList<>();
    }

    private static FlowStats flowStats(String flowName, List<FlowTrace> traces) {
        Map<String, Long> outcomes = newOutcomeCounts();
        List<Long> durations = new ArrayList<>();
        Map<String, StepAcc> steps = new LinkedHashMap<>();
        FlowStats stats = new FlowStats();
        for (FlowTrace trace : traces) {
            String outcome = outcomeOf(trace);
            outcomes.merge(outcome, 1L, Long::sum);
            if ("WAITING".equals(outcome)) {
                stats.currentlyWaiting++;
            } else {
                durations.add(Math.max(0L, trace.endedAtEpochMs() - trace.startedAtEpochMs()));
            }
            for (StepTrace step : trace.steps()) {
                StepAcc acc = steps.computeIfAbsent(step.stepName() + "\u0000" + step.stepType(), key -> {
                    StepAcc created = new StepAcc();
                    created.name = step.stepName();
                    created.type = step.stepType();
                    return created;
                });
                acc.minIndex = Math.min(acc.minIndex, step.stepIndex());
                acc.reached++;
                if (FlowTraceMerge.isParked(step)) {
                    acc.parked++;
                    continue;
                }
                if (step.outcome() == StepOutcome.FAILED) {
                    acc.failed++;
                } else {
                    acc.ok++;
                }
                if (longValue(step.info().get(FlowTraceMerge.STEP_ATTEMPTS_INFO), 1L) > 1L) {
                    acc.retried++;
                }
                long service = Math.max(0L, step.endedAtEpochMs() - step.startedAtEpochMs());
                Object waited = step.info().get(FlowTraceMerge.WAITED_MS_INFO);
                long wait = waited instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
                acc.service.add(service);
                acc.elapsed.add(service + wait);
                if (waited instanceof Number) {
                    acc.wait.add(wait);
                    stats.waits.add(wait);
                }
            }
        }

        List<StepAcc> ordered = new ArrayList<>(steps.values());
        ordered.sort(Comparator.comparingInt((StepAcc acc) -> acc.minIndex).thenComparing(acc -> acc.name));
        List<Map<String, Object>> stepJson = new ArrayList<>();
        for (StepAcc acc : ordered) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("stepName", acc.name);
            json.put("stepType", acc.type);
            json.put("stepIndex", acc.minIndex);
            json.put("reached", acc.reached);
            json.put("ok", acc.ok);
            json.put("failed", acc.failed);
            json.put("waiting", acc.parked);
            json.put("retried", acc.retried);
            json.put("serviceMs", summary(acc.service));
            json.put("waitMs", summary(acc.wait));
            json.put("elapsedMs", summary(acc.elapsed));
            stepJson.add(json);
        }
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> json : stepJson) {
            if (((Map<?, ?>) json.get("elapsedMs")).get("count").equals(0L)) {
                continue;
            }
            ranked.add(json);
        }
        ranked.sort(rankOrder());
        stats.rankedSteps = ranked;

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("flowName", flowName);
        json.put("executions", traces.size());
        json.put("byOutcome", outcomes);
        json.put("currentlyWaiting", stats.currentlyWaiting);
        json.put("durationMs", summary(durations));
        json.put("waitMs", summary(stats.waits));
        json.put("slowestStep", ranked.isEmpty() ? null : ranked.get(0).get("stepName"));
        json.put("steps", stepJson);
        stats.json = json;
        return stats;
    }

    /** Median elapsed desc, then p95 elapsed desc, then step name. */
    private static Comparator<Map<String, Object>> rankOrder() {
        return Comparator
                .comparingDouble((Map<String, Object> row) -> -metric(row, "medianMs"))
                .thenComparingDouble(row -> -metric(row, "p95Ms"))
                .thenComparing(row -> String.valueOf(row.get("stepName")));
    }

    private static double metric(Map<String, Object> row, String key) {
        Object elapsed = row.get("elapsedMs");
        if (elapsed instanceof Map<?, ?> map && map.get(key) instanceof Number number) {
            return number.doubleValue();
        }
        return 0d;
    }

    static String outcomeOf(FlowTrace trace) {
        if (trace.outcome() == StepOutcome.OK) {
            return "OK";
        }
        for (StepTrace step : trace.steps()) {
            if (FlowTraceMerge.isParked(step)) {
                return "WAITING";
            }
        }
        return "FAILED";
    }

    private static Map<String, Long> newOutcomeCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("OK", 0L);
        counts.put("FAILED", 0L);
        counts.put("WAITING", 0L);
        return counts;
    }

    static Map<String, Object> summary(List<Long> samples) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", (long) samples.size());
        if (samples.isEmpty()) {
            out.put("medianMs", null);
            out.put("p95Ms", null);
            out.put("maxMs", null);
            out.put("totalMs", 0L);
            return out;
        }
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compare);
        out.put("medianMs", median(sorted));
        out.put("p95Ms", sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1));
        out.put("maxMs", sorted.get(sorted.size() - 1));
        out.put("totalMs", sorted.stream().mapToLong(Long::longValue).sum());
        return out;
    }

    private static Number median(List<Long> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        long sum = sorted.get(n / 2 - 1) + sorted.get(n / 2);
        return sum % 2 == 0 ? (Number) (sum / 2) : (Number) (sum / 2.0);
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
