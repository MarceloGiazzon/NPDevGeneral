package com.finalexec.db;

import com.npdev.kernel.storage.sql.PostgresDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.npdev.test.postgres.PostgresTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-34 (boundary B12, POSTURAL_LIFT_PLAN_2026-09-07.md package P6): the ONLY test that proves the
 * collective mode does what it says -- a two-hook set on an engine with transactional DDL rolls back
 * as ONE unit when the second hook's verify fails, so the first hook's already-executed change is
 * GONE afterwards. Guarded by {@link PostgresTestSupport#dataSource()}'s own profile check, so it
 * SKIPS cleanly on machines with Postgres disabled (scripts/policy/local-test-profile.json has
 * enabledEngines [h2, sqlserver]) and only runs where Postgres is real (CI, or
 * NPDEV_TEST_PROFILE_ENGINES=postgres) -- the ledger item's verification says exactly that.
 *
 * <p>{@code @Tag("integration")}, excluded from the plain {@code test} task and routed to
 * {@code integrationTest} instead (build.gradle / build.gradle.template, same pattern as
 * {@link SchemaLifecycleExecutorPostgresProofMatrixTest}) -- {@code PostgresTestSupport}'s
 * {@code Assumptions.assumeTrue} skip never fires on Windows CI, because merely loading the
 * {@code PostgresTestSupport} class already constructs a {@code PostgreSQLContainer} (a field
 * initializer, not gated by the assumption check), which fails outright where Docker cannot run
 * Linux containers -- windows-latest GitHub runners. Compile-time exclusion, not the runtime
 * assumption, is what actually keeps this off that job.
 */
@Tag("integration")
class ConversionHookRunnerCollectiveRollbackTest {

    private final DataSource dataSource = PostgresTestSupport.dataSource();
    private final List<String[]> history = new ArrayList<>();
    private final ConversionHookRunner.HistoryWriter historyWriter =
            (label, outcome, details) -> history.add(new String[] {label, outcome, String.valueOf(details)});

    @Test
    void twoHookSetRollsBackAsOneUnitWhenTheSecondHooksVerifyFails() throws SQLException {
        // p75-order-1-a (ascending-id BEFORE p75-verifyfail) succeeds -- its convert.sql adds col_a
        // and it has no verify. p75-verifyfail then fails (its verifyExpect 999 can never match).
        // Because Postgres honours transactional DDL, the WHOLE set must roll back: col_a is gone.
        SqlDialects.setActive(PostgresDialect.INSTANCE);
        System.setProperty("npdev.schema.conversionHooks.atomicity", "collective");
        try {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE p75_order (id BIGINT PRIMARY KEY)");
                statement.execute("CREATE TABLE p75_verifyfail (id BIGINT PRIMARY KEY)");
            }
            SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                    "Postgres", "jdbc", true, "sha256:p6-collective-rollback",
                    List.of(), List.of("p75_order", "p75_verifyfail"),
                    Map.of("p75_order", List.of("id", "col_a"), "p75_verifyfail", List.of("id", "status")),
                    Map.of("p75_order", List.of(), "p75_verifyfail", List.of()),
                    Map.of("p75_order", Map.of("id", "BIGINT"), "p75_verifyfail", Map.of("id", "BIGINT")),
                    Map.of(), Map.of(),
                    false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                    Map.of("p75_order", List.of("col_a"), "p75_verifyfail", List.of("status")),
                    Map.of(), Map.of(), Map.of());

            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> ConversionHookRunner.run(dataSource, manifest, historyWriter));
            assertTrue(refusal.getMessage().contains("B12:collective_rollback:"), refusal.getMessage());
            assertTrue(refusal.getMessage().contains("p75-verifyfail"),
                    "the refusal names the hook that failed: " + refusal.getMessage());
            assertTrue(refusal.getMessage().contains("2 hook(s)"),
                    "the refusal names how many hooks were rolled back: " + refusal.getMessage());

            try (Connection connection = dataSource.getConnection()) {
                assertFalse(hasColumn(connection, "p75_order", "col_a"),
                        "the FIRST hook's change must be GONE -- the set rolled back as one unit");
                assertFalse(hasColumn(connection, "p75_verifyfail", "status"),
                        "the failing hook's own convert must be gone too");
            }

            List<String> outcomes = history.stream().map(row -> row[1]).toList();
            assertTrue(outcomes.contains("HOOK_STARTED"), outcomes.toString());
            assertTrue(outcomes.contains("HOOK_APPLIED"),
                    "the first hook must have written its HOOK_APPLIED row -- history survives the rollback "
                            + "by design (separate connection): " + outcomes);
            assertTrue(outcomes.contains("COLLECTIVE_ROLLED_BACK"), outcomes.toString());
        } finally {
            System.clearProperty("npdev.schema.conversionHooks.atomicity");
            SqlDialects.resetActiveForTesting();
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (var resultSet = connection.getMetaData()
                .getColumns(null, null, table.toUpperCase(java.util.Locale.ROOT), null)) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}