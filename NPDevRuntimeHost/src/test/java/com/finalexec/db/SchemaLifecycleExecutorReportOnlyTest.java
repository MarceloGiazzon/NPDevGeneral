package com.finalexec.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SER-P6.4: the REPORT_ONLY verdict-to-exit-code mapping ({@link SchemaLifecycleExecutor#codeFor}),
 * the exact logic {@link SchemaLifecycleExecutor#reportOnlyExitCode} uses. 0 = NO_CHANGES/SAFE,
 * 2 = NEEDS_ATTENTION, 3 = DESTRUCTIVE. This test never touches a DataSource or calls
 * {@code System.exit} — {@code reportOnlyExitCode} itself is exercised end-to-end only via the real
 * boot path (no manifest on the test classpath makes it return NO_CHANGES, which is already covered by
 * {@link SchemaImpactFacadeH2Test}).
 */
class SchemaLifecycleExecutorReportOnlyTest {

    @Test
    void noChangesAndSafeMapToZero() {
        assertEquals(0, SchemaLifecycleExecutor.codeFor(ImpactReport.Verdict.NO_CHANGES));
        assertEquals(0, SchemaLifecycleExecutor.codeFor(ImpactReport.Verdict.SAFE));
    }

    @Test
    void needsAttentionMapsToTwo() {
        assertEquals(2, SchemaLifecycleExecutor.codeFor(ImpactReport.Verdict.NEEDS_ATTENTION));
    }

    @Test
    void destructiveMapsToThree() {
        assertEquals(3, SchemaLifecycleExecutor.codeFor(ImpactReport.Verdict.DESTRUCTIVE));
    }
}
