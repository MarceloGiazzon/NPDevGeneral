package com.finalexec;

import com.finalexec.boundary.BoundaryBootException;
import com.finalexec.boundary.BoundaryViolation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Item 6, SUPPORT_FEATURES_PLAN_2026-08-26. Unit coverage for {@link
 * FinalExecApplication#findBoundaryBootException} -- the cause-chain walk that decides whether a
 * boot failure gets the legible exit-4 refusal or the ordinary rethrow. The live, end-to-end proof
 * (a real second instance colliding on the canary's locked H2Local database, clean output, exit
 * code 4, no stack trace) ran outside this module's own JaCoCo-measured test task, per this
 * project's standing "capability is proven by running it against a real app" rule; {@code main}
 * itself stays untested here since it calls {@link System#exit}.
 */
class FinalExecApplicationTest {

    @Test
    void returnsNullWhenNoBoundaryBootExceptionInTheCauseChain() {
        RuntimeException failure = new RuntimeException("a genuine bug", new IllegalStateException("root cause"));

        assertNull(FinalExecApplication.findBoundaryBootException(failure));
    }

    @Test
    void findsTheBoundaryBootExceptionWhenItIsTheFailureItself() {
        BoundaryViolation violation = new BoundaryViolation("B4", "boot", "migration lock held", Instant.now());
        BoundaryBootException boundaryBootException = new BoundaryBootException(violation);

        assertSame(boundaryBootException, FinalExecApplication.findBoundaryBootException(boundaryBootException));
    }

    @Test
    void findsTheBoundaryBootExceptionNestedDeepInsideSpringsWrapperFailures() {
        BoundaryViolation violation = new BoundaryViolation("B5", "boot", "schema ahead of model", Instant.now());
        BoundaryBootException boundaryBootException = new BoundaryBootException(violation);
        RuntimeException wrapped = new RuntimeException(
                "Error creating bean", new RuntimeException("Initialization failed", boundaryBootException));

        assertSame(boundaryBootException, FinalExecApplication.findBoundaryBootException(wrapped));
    }
}
