package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link DialectTestSupport#shouldRun} -- the filter that decides which dialects a conformance
 * run covers.
 *
 * <p><b>Why this needs its own test.</b> Every bug this filter has already had was a SILENT one, and
 * a silent bug in test SELECTION is uniquely bad: the suite reports green either way, and the
 * evidence for the thing you wanted is the absence of an error.
 *
 * <ul>
 *   <li>Run 31264977219 handed {@code h2} to a container-only job, producing 13 failures per job
 *       that had nothing to do with any engine.</li>
 *   <li>The first version of {@code -Dnpdev.dialect.only} did nothing at all, because Gradle sets
 *       {@code -D} on its own JVM and not on the forked test JVM. All four dialects kept running and
 *       the run reported green -- CI would have believed each job was scoped to one engine while
 *       every job ran all of them.</li>
 * </ul>
 *
 * <p>Both are the same defect family as everything else in this seam: a wrong answer that looks
 * exactly like a right one.
 */
@DisplayName("Conformance selection -- which dialects a run actually covers")
class DialectTestSupportSelectionTest {

    @AfterEach
    void clearScoping() {
        System.clearProperty(DialectTestSupport.ONLY_PROPERTY);
    }

    @Test
    @DisplayName("unscoped: every registered dialect runs on the local backend")
    void localBackendRunsEverything() {
        // H2 impersonates each engine locally, so there is no dialect the local backend cannot serve.
        for (SqlDialect dialect : SqlDialects.all()) {
            assertTrue(DialectTestSupport.shouldRun(dialect),
                    dialect.name() + " should run on the local backend");
        }
    }

    @Test
    @DisplayName("-Dnpdev.dialect.only selects exactly one dialect and excludes the rest")
    void scopingSelectsOneDialect() {
        System.setProperty(DialectTestSupport.ONLY_PROPERTY, "mysql");
        assertTrue(DialectTestSupport.shouldRun(MySqlDialect.INSTANCE));
        assertTrue(!DialectTestSupport.shouldRun(PostgresDialect.INSTANCE));
        assertTrue(!DialectTestSupport.shouldRun(SqlServerDialect.INSTANCE));
        assertTrue(!DialectTestSupport.shouldRun(H2Dialect.INSTANCE));
    }

    @Test
    @DisplayName("the property is matched case-insensitively, but only against real dialect names")
    void scopingIsCaseInsensitive() {
        System.setProperty(DialectTestSupport.ONLY_PROPERTY, "MySQL");
        assertTrue(DialectTestSupport.shouldRun(MySqlDialect.INSTANCE));
        assertTrue(!DialectTestSupport.shouldRun(PostgresDialect.INSTANCE));
    }

    @Test
    @DisplayName("a TYPO in the property fails loudly rather than selecting nothing")
    void scopingTypoIsRefused() {
        // The trap the fix plan named explicitly: "-Dnpdev.dialect.only=mysqle" must not quietly
        // select zero dialects and let the job report green on zero tests. A suite that verifies
        // nothing while looking like proof is strictly worse than the bug being fixed.
        System.setProperty(DialectTestSupport.ONLY_PROPERTY, "mysqle");
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> DialectTestSupport.shouldRun(MySqlDialect.INSTANCE));
        assertTrue(refusal.getMessage().contains("mysqle"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Known:"), refusal.getMessage());
    }

    @Test
    @DisplayName("blank or absent scoping means everything, so a local run needs no configuration")
    void blankScopingRunsEverything() {
        System.setProperty(DialectTestSupport.ONLY_PROPERTY, "   ");
        assertEquals(SqlDialects.all().size(),
                (int) SqlDialects.all().stream().filter(DialectTestSupport::shouldRun).count());
    }

    @Test
    @DisplayName("a filtered-out dialect yields NO test cases -- never a passing one")
    void filteredDialectsProduceNoCases() {
        // The property that makes the filter safe. If exclusion produced a passing case instead of
        // no case, "sqlserver: 13 passed" could mean "sqlserver never ran" -- the silent-answer
        // defect wearing a green tick.
        System.setProperty(DialectTestSupport.ONLY_PROPERTY, "postgres");
        long selected = SqlDialects.all().stream().filter(DialectTestSupport::shouldRun).count();
        assertEquals(1, selected);
    }
}
