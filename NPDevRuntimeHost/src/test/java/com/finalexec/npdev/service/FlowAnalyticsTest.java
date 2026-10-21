package com.finalexec.npdev.service;

import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** FLOW-1: the numbers FlowAnalytics reports, checked against hand-computed values. */
class FlowAnalyticsTest {

    private static StepTrace step(int index, String name, long start, long durationMs, StepOutcome outcome, Map<String, Object> info) {
        return new StepTrace(index, name, "CAPABILITY_CALL", start, start + durationMs, outcome, info, List.of(), null);
    }

    private static FlowTrace trace(String id, String flow, long start, long end, StepOutcome outcome, StepTrace... steps) {
        return new FlowTrace(new FlowTraceMeta(id, "corr-" + id, flow, "t", "a", Map.of()), start, end, outcome, List.of(steps));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Test
    void medianP95AndTheSlowestStepMatchHandComputedValues() {
        // Flow "Order": step "pick" durations 10,20,30,40 (median 25, p95 = 4th = 40);
        // step "pack" durations 5,5,5,100 (median 5, p95 100): pick is the slower step by median.
        List<FlowTrace> traces = new ArrayList<>();
        long[] pick = {10, 20, 30, 40};
        long[] pack = {5, 5, 5, 100};
        for (int i = 0; i < 4; i++) {
            long t0 = 1000L * (i + 1);
            traces.add(trace("e" + i, "Order", t0, t0 + pick[i] + pack[i], StepOutcome.OK,
                    step(0, "pick", t0, pick[i], StepOutcome.OK, Map.of()),
                    step(1, "pack", t0 + pick[i], pack[i], StepOutcome.OK, Map.of())));
        }
        Map<String, Object> out = FlowAnalytics.compute(traces);

        assertEquals(4, out.get("executionCount"));
        assertEquals(4L, map(out.get("byOutcome")).get("OK"));
        Map<String, Object> order = list(out.get("flows")).get(0);
        assertEquals("pick", order.get("slowestStep"));
        List<Map<String, Object>> steps = list(order.get("steps"));
        assertEquals(List.of("pick", "pack"), steps.stream().map(s -> s.get("stepName")).toList(), "ordered by step index");
        Map<String, Object> pickService = map(steps.get(0).get("serviceMs"));
        assertEquals(25.0, ((Number) pickService.get("medianMs")).doubleValue());
        assertEquals(40L, pickService.get("p95Ms"));
        Map<String, Object> packService = map(steps.get(1).get("serviceMs"));
        assertEquals(5L, packService.get("medianMs"));
        assertEquals(100L, packService.get("p95Ms"));
        assertEquals("pick", list(out.get("bottlenecks")).get(0).get("stepName"));
        assertEquals(1, list(out.get("bottlenecks")).get(0).get("rank"));
        // execution duration: 15, 25, 35, 140 -> median 30, p95 140
        Map<String, Object> duration = map(order.get("durationMs"));
        assertEquals(30.0, ((Number) duration.get("medianMs")).doubleValue());
        assertEquals(140L, duration.get("p95Ms"));
    }

    @Test
    void p95UsesNearestRankOverTwentySamples() {
        List<Long> samples = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            samples.add(i);
        }
        Map<String, Object> summary = FlowAnalytics.summary(samples);
        assertEquals(19L, summary.get("p95Ms"), "ceil(0.95*20)=19 -> the 19th smallest");
        assertEquals(10.5, ((Number) summary.get("medianMs")).doubleValue());
        assertEquals(210L, summary.get("totalMs"));
        assertNull(FlowAnalytics.summary(List.of()).get("medianMs"));
    }

    @Test
    void humanWaitIsCountedSeparatelyAndCanMakeAFastStepTheBottleneck() {
        // "approve" is an await that took 1ms of service time but the human took 5000ms/7000ms.
        List<FlowTrace> traces = List.of(
                trace("w1", "Approval", 1000, 9000, StepOutcome.OK,
                        step(0, "prepare", 1000, 50, StepOutcome.OK, Map.of()),
                        step(1, "approve", 6050, 1, StepOutcome.OK, Map.of("waitedMs", 5000L))),
                trace("w2", "Approval", 2000, 12000, StepOutcome.OK,
                        step(0, "prepare", 2000, 70, StepOutcome.OK, Map.of()),
                        step(1, "approve", 9070, 1, StepOutcome.OK, Map.of("waitedMs", 7000L))),
                trace("w3", "Approval", 3000, 3300, StepOutcome.FAILED,
                        step(0, "prepare", 3000, 60, StepOutcome.OK, Map.of()),
                        step(1, "approve", 3200, 100, StepOutcome.FAILED, Map.of("awaitedEventStatus", "WAITING"))));
        Map<String, Object> out = FlowAnalytics.compute(traces);

        Map<String, Object> outcomes = map(out.get("byOutcome"));
        assertEquals(2L, outcomes.get("OK"));
        assertEquals(1L, outcomes.get("WAITING"));
        assertEquals(0L, outcomes.get("FAILED"));
        Map<String, Object> humanWait = map(out.get("humanWait"));
        assertEquals(1L, humanWait.get("currentlyWaitingExecutions"));
        assertEquals(12000L, map(humanWait.get("resumedWaits")).get("totalMs"));
        assertEquals(2L, map(humanWait.get("resumedWaits")).get("count"));

        Map<String, Object> approval = list(out.get("flows")).get(0);
        assertEquals("approve", approval.get("slowestStep"), "waits count toward elapsed time");
        Map<String, Object> approve = list(approval.get("steps")).get(1);
        assertEquals(1L, approve.get("waiting"), "the parked entry is a live count");
        assertEquals(2L, map(approve.get("serviceMs")).get("count"), "a parked entry is never a duration sample");
        assertEquals(6001L, ((Number) map(approve.get("elapsedMs")).get("medianMs")).longValue(),
                "median of 5001 and 7001 is 6001");
        // Duration stats exclude the still-waiting execution.
        assertEquals(2L, map(approval.get("durationMs")).get("count"));
    }

    @Test
    void failuresAndRetriesArePerStepCounters() {
        List<FlowTrace> traces = List.of(
                trace("f1", "Ship", 1000, 1200, StepOutcome.FAILED,
                        step(0, "label", 1000, 10, StepOutcome.OK, Map.of()),
                        step(1, "dispatch", 1010, 190, StepOutcome.FAILED, Map.of())),
                trace("f2", "Ship", 2000, 2200, StepOutcome.OK,
                        step(0, "label", 2000, 10, StepOutcome.OK, Map.of()),
                        step(1, "dispatch", 2010, 190, StepOutcome.OK, Map.of("stepAttempts", 2L))));
        Map<String, Object> dispatch = list(list(FlowAnalytics.compute(traces).get("flows")).get(0).get("steps")).get(1);
        assertEquals(2L, dispatch.get("reached"));
        assertEquals(1L, dispatch.get("failed"));
        assertEquals(1L, dispatch.get("ok"));
        assertEquals(1L, dispatch.get("retried"));
    }
}
