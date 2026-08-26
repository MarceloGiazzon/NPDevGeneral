package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 1, SUPPORT_FEATURES_PLAN_2026-08-26. Unit coverage for the argument-parsing and
 * connection-failure branches of {@link SchemaVerifyMain#run} -- the full end-to-end proof
 * (baseline match / drift / revert / unreachable, all four exit codes) ran against a real canary
 * app outside any test task, per this project's standing "capability is proven by running it
 * against a real app" rule. {@code SchemaVerifyMain} lives in runtimehost-core, which generated
 * apps consume as a prebuilt library jar -- these tests run for real (and would catch a real
 * regression), but do NOT move the RuntimeHost JaCoCo ratchet, which only scans a generated app's
 * own {@code sourceSets.main.output}, not classes pulled in from a dependency jar. This file is
 * copied into every generated app's own test source, so a real schema-realization-manifest.json
 * IS on this test's classpath (unlike SchemaImpactFacadeH2Test's premise) -- these tests
 * deliberately avoid asserting on that manifest's app-specific contents.
 */
class SchemaVerifyMainTest {

    @Test
    void unrecognizedArgumentRefusesWithCouldNotDetermine() {
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int exitCode = SchemaVerifyMain.run(
                new String[] {"--bogus"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        assertEquals(SchemaVerifyMain.EXIT_COULD_NOT_DETERMINE, exitCode);
        assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("unrecognized or incomplete argument"));
    }

    @Test
    void missingUrlRefusesWithCouldNotDetermine() {
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int exitCode = SchemaVerifyMain.run(
                new String[] {"--json"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        assertEquals(SchemaVerifyMain.EXIT_COULD_NOT_DETERMINE, exitCode);
        assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("usage:"));
    }

    @Test
    void invalidTargetIsHandledWithoutCrashing() {
        // An unregistered JDBC subprotocol always fails DriverManager.getConnection with a
        // SQLException -- but this file is copied into every generated app's own test source, and
        // an InMemory-storage app short-circuits on manifest.physicalDatabase()=false BEFORE ever
        // touching the URL (SchemaVerifyMain.java:94), returning EXIT_MATCHES instead. Branch on
        // the exit code actually produced rather than assuming which storage mode this particular
        // app declares, so this test exercises a real code path under either one.
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int exitCode = SchemaVerifyMain.run(
                new String[] {"--url", "jdbc:npdev-unsupported-test-scheme://nope"},
                new PrintStream(outBuffer, true, StandardCharsets.UTF_8),
                new PrintStream(errBuffer, true, StandardCharsets.UTF_8));

        if (exitCode == SchemaVerifyMain.EXIT_MATCHES) {
            String out = outBuffer.toString(StandardCharsets.UTF_8);
            assertTrue(out.contains("no physical database"), () -> "unexpected stdout: " + out);
        } else {
            assertEquals(SchemaVerifyMain.EXIT_COULD_NOT_DETERMINE, exitCode);
            String err = errBuffer.toString(StandardCharsets.UTF_8);
            assertTrue(err.contains("could not connect to"), () -> "unexpected stderr: " + err);
        }
    }

    /**
     * The arm nobody exercised, which is why the bug shipped: every test above lands on
     * EXIT_COULD_NOT_DETERMINE, so the matches-vs-drift decision itself had zero coverage.
     *
     * <p>Measured RED before the fix: verifying a real canary app against an EMPTY database
     * reported {@code verdict: SAFE} with two SAFE_TABLE_CREATE rows -- the entire schema missing --
     * and exited 0 while the CLI's own contract says 0 means "matches". Only NO_CHANGES is a match.
     */
    @Test
    void onlyNoChangesCountsAsAMatch() {
        assertEquals(SchemaVerifyMain.EXIT_MATCHES,
                SchemaVerifyMain.exitCodeFor(ImpactReport.Verdict.NO_CHANGES));

        // SAFE means "changes exist and are safe to apply" -- an empty database verifies as SAFE.
        assertEquals(SchemaVerifyMain.EXIT_DRIFT,
                SchemaVerifyMain.exitCodeFor(ImpactReport.Verdict.SAFE));
        assertEquals(SchemaVerifyMain.EXIT_DRIFT,
                SchemaVerifyMain.exitCodeFor(ImpactReport.Verdict.NEEDS_ATTENTION));
        assertEquals(SchemaVerifyMain.EXIT_DRIFT,
                SchemaVerifyMain.exitCodeFor(ImpactReport.Verdict.DESTRUCTIVE));
    }

    /** Every Verdict maps to a real exit code -- a new enum constant must not silently read as a match. */
    @Test
    void everyVerdictMapsToMatchesOrDrift() {
        for (ImpactReport.Verdict verdict : ImpactReport.Verdict.values()) {
            int exitCode = SchemaVerifyMain.exitCodeFor(verdict);
            assertTrue(exitCode == SchemaVerifyMain.EXIT_MATCHES || exitCode == SchemaVerifyMain.EXIT_DRIFT,
                    () -> verdict + " mapped to unexpected exit code " + SchemaVerifyMain.exitCodeFor(verdict));
        }
    }
}
