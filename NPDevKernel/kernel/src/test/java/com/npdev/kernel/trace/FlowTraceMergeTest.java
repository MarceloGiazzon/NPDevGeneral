package com.npdev.kernel.trace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FLOW-1: what merge-on-save keeps, replaces, and records when a flow trace is saved a second time. */
class FlowTraceMergeTest {

    private static StepTrace step(int index, String name, long start, long end, StepOutcome outcome, Map<String, Object> info) {
        return new StepTrace(index, name, "CAPABILITY_CALL", start, end, outcome, info, List.of(), null);
    }

    private static FlowTrace trace(long start, long end, StepOutcome outcome, Map<String, Object> tags, StepTrace... steps) {
        return new FlowTrace(new FlowTraceMeta("exec-1", "corr-1", "Flow", "tenant-a", "actor", tags),
                start, end, outcome, List.of(steps));
    }

    private static Map<String, Object> fresh() {
        return Map.of("startStepIndex", 0);
    }

    private static Map<String, Object> resumedAt(int index) {
        return Map.of("startStepIndex", index, "resumed", true);
    }

    @Test
    void aFreshRunReplacesAnythingStoredBefore() {
        FlowTrace prior = trace(1000, 1100, StepOutcome.OK, fresh(), step(0, "a", 1000, 1100, StepOutcome.OK, Map.of()));
        FlowTrace next = trace(5000, 5200, StepOutcome.OK, fresh(), step(0, "a", 5000, 5200, StepOutcome.OK, Map.of()));
        assertSame(next, FlowTraceMerge.merge(prior, next));
        assertSame(next, FlowTraceMerge.merge(null, next));
    }

    @Test
    void resumeKeepsEarlierStepsKeepsOriginalStartAndRecordsTheWait() {
        FlowTrace parked = trace(1000, 1300, StepOutcome.FAILED, fresh(),
                step(0, "charge", 1000, 1100, StepOutcome.OK, Map.of()),
                step(1, "await-approval", 1200, 1300, StepOutcome.FAILED, Map.of("awaitedEventStatus", "WAITING")));
        FlowTrace resumed = trace(9000, 9050, StepOutcome.OK, resumedAt(1),
                step(1, "await-approval", 9000, 9010, StepOutcome.OK, Map.of("awaitedEventFoundEventId", "e1")),
                step(2, "ship", 9010, 9050, StepOutcome.OK, Map.of()));

        FlowTrace merged = FlowTraceMerge.merge(parked, resumed);

        assertEquals(1000, merged.startedAtEpochMs(), "the execution started with its FIRST segment");
        assertEquals(9050, merged.endedAtEpochMs());
        assertEquals(StepOutcome.OK, merged.outcome());
        assertEquals(List.of("charge", "await-approval", "ship"), merged.steps().stream().map(StepTrace::stepName).toList());
        StepTrace awaited = merged.steps().get(1);
        assertEquals(StepOutcome.OK, awaited.outcome(), "the parked entry is replaced, not duplicated");
        assertEquals(1300L, ((Number) awaited.info().get("waitStartedAtEpochMs")).longValue());
        assertEquals(7700L, ((Number) awaited.info().get("waitedMs")).longValue(), "resumed start 9000 - parked end 1300");
        assertFalse(awaited.info().containsKey("stepAttempts"));
        assertEquals(2L, ((Number) merged.meta().tags().get("segments")).longValue());
        assertEquals(1, merged.steps().stream().filter(s -> s.stepName().equals("await-approval")).count());
    }

    @Test
    void aQuietResumeThatParksAgainKeepsTheOriginalParkTimeAndRecordsNoWaitYet() {
        FlowTrace parked = trace(1000, 1300, StepOutcome.FAILED, fresh(),
                step(0, "await-approval", 1200, 1300, StepOutcome.FAILED, Map.of("awaitedEventStatus", "WAITING")));
        FlowTrace quiet = trace(3000, 3010, StepOutcome.FAILED, resumedAt(0),
                step(0, "await-approval", 3000, 3010, StepOutcome.FAILED, Map.of("awaitedEventStatus", "WAITING")));
        FlowTrace afterQuiet = FlowTraceMerge.merge(parked, quiet);
        StepTrace stillParked = afterQuiet.steps().get(0);
        assertEquals(1300L, ((Number) stillParked.info().get("waitStartedAtEpochMs")).longValue());
        assertFalse(stillParked.info().containsKey("waitedMs"), "still waiting: no wait duration yet");

        FlowTrace finished = trace(8000, 8020, StepOutcome.OK, resumedAt(0),
                step(0, "await-approval", 8000, 8020, StepOutcome.OK, Map.of()));
        StepTrace done = FlowTraceMerge.merge(afterQuiet, finished).steps().get(0);
        assertEquals(6700L, ((Number) done.info().get("waitedMs")).longValue(),
                "the clock started at the FIRST park (1300), not at the last quiet resume");
        assertEquals(3L, ((Number) FlowTraceMerge.merge(afterQuiet, finished).meta().tags().get("segments")).longValue());
    }

    @Test
    void operatorRetryReplacesTheFailedStepAndCountsTheAttempt() {
        FlowTrace failed = trace(1000, 1200, StepOutcome.FAILED, fresh(),
                step(0, "charge", 1000, 1100, StepOutcome.OK, Map.of()),
                step(1, "ship", 1100, 1200, StepOutcome.FAILED, Map.of()));
        FlowTrace retried = trace(4000, 4100, StepOutcome.OK, resumedAt(1),
                step(1, "ship", 4000, 4100, StepOutcome.OK, Map.of()));
        FlowTrace merged = FlowTraceMerge.merge(failed, retried);
        assertEquals(2, merged.steps().size(), "no duplicate ship step");
        StepTrace ship = merged.steps().get(1);
        assertEquals(StepOutcome.OK, ship.outcome());
        assertEquals(2L, ((Number) ship.info().get("stepAttempts")).longValue());
        assertFalse(ship.info().containsKey("waitedMs"), "a retry is not a human wait");

        FlowTrace retriedAgain = trace(7000, 7100, StepOutcome.OK, resumedAt(1),
                step(1, "ship", 7000, 7100, StepOutcome.OK, Map.of()));
        assertEquals(3L, ((Number) FlowTraceMerge.merge(merged, retriedAgain).steps().get(1).info().get("stepAttempts")).longValue());
    }

    @Test
    void aRetryInTheSameMillisecondAsTheFailedAttemptIsStillARetry() {
        // a fast retry can carry the failed step's exact timestamps; only the outcome tells it from a duplicate save
        FlowTrace failed = trace(1000, 1000, StepOutcome.FAILED, fresh(),
                step(0, "charge", 1000, 1000, StepOutcome.OK, Map.of()),
                step(1, "ship", 1000, 1000, StepOutcome.FAILED, Map.of()));
        FlowTrace retried = trace(1000, 1000, StepOutcome.OK, resumedAt(1),
                step(1, "ship", 1000, 1000, StepOutcome.OK, Map.of()));
        FlowTrace merged = FlowTraceMerge.merge(failed, retried);
        assertEquals(StepOutcome.OK, merged.outcome());
        assertEquals(StepOutcome.OK, merged.steps().get(1).outcome());
        assertEquals(2L, ((Number) merged.steps().get(1).info().get("stepAttempts")).longValue());
    }

    @Test
    void savingTheSameSegmentTwiceIsIdempotent() {
        FlowTrace parked = trace(1000, 1300, StepOutcome.FAILED, fresh(),
                step(0, "await-approval", 1200, 1300, StepOutcome.FAILED, Map.of("awaitedEventStatus", "WAITING")));
        FlowTrace resumed = trace(9000, 9050, StepOutcome.OK, resumedAt(0),
                step(0, "await-approval", 9000, 9010, StepOutcome.OK, Map.of()));
        FlowTrace once = FlowTraceMerge.merge(parked, resumed);
        FlowTrace twice = FlowTraceMerge.merge(once, resumed);
        assertEquals(once, twice);
        assertNull(twice.steps().get(0).info().get("stepAttempts"), "the duplicate save must not read as a retry");
    }

    @Test
    void aStepThatNoLongerMatchesByNameIsAppendedNotMerged() {
        FlowTrace prior = trace(1000, 1200, StepOutcome.FAILED, fresh(),
                step(0, "old-name", 1000, 1200, StepOutcome.FAILED, Map.of("awaitedEventStatus", "WAITING")));
        FlowTrace next = trace(3000, 3100, StepOutcome.OK, resumedAt(0),
                step(0, "new-name", 3000, 3100, StepOutcome.OK, Map.of()));
        FlowTrace merged = FlowTraceMerge.merge(prior, next);
        assertEquals(List.of("new-name"), merged.steps().stream().map(StepTrace::stepName).toList());
        assertTrue(merged.steps().get(0).info().isEmpty());
    }
}
