package com.finalexec.boundary;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 6, SUPPORT_FEATURES_PLAN_2026-08-26. Unit coverage for the description/action text {@link
 * BoundaryBootExceptionFailureAnalyzer#analyze} produces -- the live, end-to-end proof (a real
 * second instance colliding on the canary's locked H2Local database, clean output, exit code 4, no
 * stack trace) ran outside any test task, per this project's standing "capability is proven by
 * running it against a real app" rule. {@code BoundaryBootExceptionFailureAnalyzer} lives in
 * runtimehost-core, which generated apps consume as a prebuilt library jar -- this test calls the
 * same method Spring Boot's own failure reporting calls and runs for real, but does NOT move the
 * RuntimeHost JaCoCo ratchet, which only scans a generated app's own {@code
 * sourceSets.main.output}, not classes pulled in from a dependency jar.
 */
class BoundaryBootExceptionFailureAnalyzerTest {

    @Test
    void analyzeNamesTheBoundaryAndPointsAtNpdevWhy() {
        BoundaryViolation violation = new BoundaryViolation(
                "B4", "boot", "B4:migration_lock_held:Another NPDev instance is currently migrating this database",
                Instant.now());
        BoundaryBootException exception = new BoundaryBootException(violation);

        FailureAnalysis analysis = new BoundaryBootExceptionFailureAnalyzer().analyze(exception, exception);

        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("NPDev refused to boot"));
        assertTrue(analysis.getDescription().contains(violation.message()));
        assertTrue(analysis.getAction().contains("boundary B4"));
        assertTrue(analysis.getAction().contains("npdev why B4"));
        assertSame(exception, analysis.getCause());
    }

    @Test
    void analyzeUsesTheActualBoundaryIdNotAHardcodedOne() {
        BoundaryViolation violation = new BoundaryViolation("B9", "restore", "single-table scope only", Instant.now());
        BoundaryBootException exception = new BoundaryBootException(violation, new IllegalStateException("root cause"));

        FailureAnalysis analysis = new BoundaryBootExceptionFailureAnalyzer().analyze(exception, exception);

        assertEquals(
                "This is a designed limit (boundary B9), not a crash in NPDev itself. Run `npdev why B9` "
                        + "for the full explanation and workaround.",
                analysis.getAction());
    }
}
