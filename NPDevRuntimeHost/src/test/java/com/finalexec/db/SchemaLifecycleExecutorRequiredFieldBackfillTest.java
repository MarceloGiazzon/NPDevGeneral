package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 5 (5.2). Integration coverage for the required-field backfill step
 * ({@code SchemaLifecycleExecutor#applyRequiredFieldBackfills}), against a real H2 database, same
 * style as {@link SchemaLifecycleExecutorInPlaceRenameTest}.
 *
 * <p>Confirms the VERIFY item recon flagged: pre-Phase-5, {@code appendAdditiveColumns} already
 * added a new required column as nullable-forever with no backfill and no enforcement -- this test
 * proves the FIXED behavior (backfill + NOT NULL enforced) and the two refusal shapes (expression
 * default only; no default at all) that replace the old silent-nullable-forever outcome.
 */
class SchemaLifecycleExecutorRequiredFieldBackfillTest {

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;

    // BOUNDARY_LIFT_PLAN_2026-09-02 package 3.1 (B2): a couple of tests below override these to
    // exercise a non-default auto-apply mode/threshold -- snapshot and restore so no test leaks its
    // override into another test in this same JVM.
    private static final String AUTO_APPLY_MODE_PROPERTY = "npdev.schema.backfill.auto-apply";
    private static final String AUTO_APPLY_MAX_ROWS_PROPERTY = "npdev.schema.backfill.auto-apply-max-rows";
    private String previousAutoApplyMode;
    private String previousAutoApplyMaxRows;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        previousAutoApplyMode = System.getProperty(AUTO_APPLY_MODE_PROPERTY);
        previousAutoApplyMaxRows = System.getProperty(AUTO_APPLY_MAX_ROWS_PROPERTY);
    }

    @AfterEach
    void tearDown() throws SQLException {
        restoreProperty(AUTO_APPLY_MODE_PROPERTY, previousAutoApplyMode);
        restoreProperty(AUTO_APPLY_MAX_ROWS_PROPERTY, previousAutoApplyMaxRows);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    @Test
    void requiredColumnWithLiteralDefaultIsBackfilledAndTightenedToNotNull() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (2, 'beta', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("status", "\"PENDING\"")),
                Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "a required field with a literal default must still resolve via the safe-additive path");
        assertFalse(result.performed(), "no destructive recreation must be performed");

        // R2 (F1): the backfill/tighten now runs in afterMigrate (the single enforcement call site),
        // not beforeMigrate.
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "status"), "the required column must have been added");
            assertTrue(isNotNull(metadata, "widgets", "status"), "the column must be enforced NOT NULL after backfill");

            assertEquals("PENDING", readStatus(connection, 1), "row 1 must be backfilled to the literal default");
            assertEquals("PENDING", readStatus(connection, 2), "row 2 must be backfilled to the literal default");

            // A NEW row supplying its own value must not be overridden by the default.
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO widgets (id, name, status, version) VALUES (3, 'gamma', 'APPROVED', 1)")) {
                insert.executeUpdate();
            }
            assertEquals("APPROVED", readStatus(connection, 3));

            // NOT NULL is really enforced, not just "happened to be backfilled" -- a null insert must fail.
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO widgets (id, name, status, version) VALUES (4, 'delta', NULL, 1)")) {
                    insert.executeUpdate();
                }
            }, "a NULL insert into the now-NOT-NULL column must be rejected by the database");
        }
    }

    @Test
    void requiredColumnWithNoDefaultRefusesAndLeavesTheColumnUnadded() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", List.of("status")),
                Map.of(),
                Map.of());

        // R2 (F1): the refusal now fires from the single afterMigrate enforcement call site. Driving
        // afterMigrate directly (its 2-arg overload reads the seeded stale fingerprint and derives a
        // mismatch) isolates the refusal to a single REFUSED history row.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("status"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("no default declared"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "status"),
                    "a refused required-field addition must never add the column at all, not even nullable");
        }

        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("REFUSED", row.outcome());
    }

    @Test
    void ownershipIsRecordedForALiveTableEvenWhenTheBackfillPassRefusesAfterward() throws SQLException {
        // B8 (Wave 2 package 2.1, boundary-lift 2026-09-02): before this package, ownership was
        // recorded ONLY at the very end of afterMigrate, after BackfillPass/UniqueConstraintPass ran.
        // A refusal in either of them -- this fixture is the SAME no-default refusal
        // requiredColumnWithNoDefaultRefusesAndLeavesTheColumnUnadded proves -- meant a table this
        // boot's flyway.migrate() already created was left with NO ownership record at all.
        // recordOwnershipForLiveManifestTables now runs FIRST in afterMigrate, so the refusal below
        // must not prevent "widgets" from being recorded as owned.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", List.of("status")),
                Map.of(),
                Map.of());

        assertThrows(IllegalStateException.class, () -> executor.afterMigrate(dataSource, manifest));

        java.util.Set<String> owned = SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource);
        assertTrue(owned != null && owned.contains("widgets"),
                "ownership must be recorded BEFORE the backfill refusal, not only after it -- otherwise "
                + "a table this boot genuinely created is left without a record (the B8 bug), even "
                + "though the boot itself still correctly refuses");
    }

    @Test
    void requiredColumnWithOnlyAnExpressionDefaultRefusesWithADistinctMessage() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "approvedAt", "version")),
                Map.of("widgets", List.of("approvedAt")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "approvedAt", "TIMESTAMP", "version", "BIGINT")),
                Map.of("widgets", List.of("approvedAt")),
                Map.of(),
                Map.of("widgets", List.of("approvedAt")));

        // R2 (F1): expression-default-only refusal now fires from the afterMigrate enforcement site.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("approvedAt"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("expression default is declared"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "approvedAt"),
                    "an expression-default-only refusal must also leave the column entirely unadded");
        }
    }

    @Test
    void secondBootAfterASuccessfulBackfillIsAConvergedNoOp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("status", "\"PENDING\"")),
                Map.of());

        executor.beforeMigrate(dataSource, manifest);
        // R2 (F1): afterMigrate performs the backfill/tighten AND stores the new fingerprint (the real
        // migrate() path does exactly this after flyway.migrate() succeeds).
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(isNotNull(metadata, "widgets", "status"), "the first boot must have backfilled + tightened the column");
        }

        // Re-running the boot (idempotent-by-check: the column already exists and is already NOT NULL,
        // and the stored fingerprint now matches) must be a pure no-op that does not re-touch the data.
        SchemaLifecycleExecutor.DestructiveRecreation second = executor.beforeMigrate(dataSource, manifest);
        assertFalse(second.performed());
        assertFalse(second.safeAdditive(), "with a matching stored fingerprint, beforeMigrate must short-circuit to a pure no-op");
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            assertEquals("PENDING", readStatus(connection, 1), "the converged re-run must not have altered existing data");
        }
    }

    // ---- Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): acknowledged expression-default backfill ----

    @Test
    void unacknowledgedExpressionDefaultWithRealExpressionTextStillRefusesUnchanged() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), quantity BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (1, 'alpha', 10)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // "riskyLookup(quantity)" is a function call -> REVIEWABLE tier (package 3.1), so this stays
        // on the unchanged ack-required path even under the default auto-apply mode. A bare "$quantity"
        // copy would now be SAFE and auto-apply -- see safeFieldCopyExpressionAutoAppliesWithoutAnyAcknowledgment
        // below for that case.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "quantity", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "quantity", "BIGINT", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "riskyLookup(quantity)")));

        // DoD (Move 9 B1): unacknowledged, an expression default still refuses the boot exactly as it
        // did before this feature existed -- no ackToken submitted anywhere in this test.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("auditQuantity"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("unless explicitly acknowledged"), refusal.getMessage());
        // B2 (docs/ACCEPTED_BOUNDARIES.md, 2026-08-25 W2.3): the refusal carries a boundaryId-linked
        // code so a boot log line or future orchestrator hook can key on it rather than on English.
        assertTrue(refusal.getMessage().contains("B2:expression_backfill_requires_ack:"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "widgets", "auditQuantity"),
                    "an unacknowledged expression backfill must never add the column at all");
        }
    }

    // ---- BOUNDARY_LIFT_PLAN_2026-09-02 package 3.1 (B2, REG-202): risk-tiered auto-apply ----

    @Test
    void safeFieldCopyExpressionAutoAppliesWithoutAnyAcknowledgment() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), quantity BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (1, 'alpha', 10)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (2, 'beta', 20)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // "$quantity" is a single-column copy -> SAFE. Default mode (safe) + default threshold
        // (10000 rows) + zero failing rows must auto-apply with NO ackToken ever inserted.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "quantity", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "quantity", "BIGINT", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "$quantity")));

        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "auditQuantity"),
                    "a SAFE expression default must auto-apply and add the column");
            assertTrue(isNotNull(metadata, "widgets", "auditQuantity"));
            assertEquals(10L, readLong(connection, "auditQuantity", 1));
            assertEquals(20L, readLong(connection, "auditQuantity", 2));
        }
    }

    @Test
    void safeExpressionDoesNotAutoApplyWhenAutoApplyModeIsNone() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), quantity BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (1, 'alpha', 10)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        System.setProperty(AUTO_APPLY_MODE_PROPERTY, "none");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "quantity", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "quantity", "BIGINT", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "$quantity")));

        // Even a SAFE candidate must not bypass the ack requirement once the operator has opted out
        // via auto-apply=none.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("B2:expression_backfill_requires_ack:"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "widgets", "auditQuantity"));
        }
    }

    @Test
    void safeExpressionOverTheRowThresholdStillRequiresAcknowledgment() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), quantity BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (1, 'alpha', 10)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        // One row affected but a zero threshold -- SAFE alone is not enough to auto-apply.
        System.setProperty(AUTO_APPLY_MAX_ROWS_PROPERTY, "0");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "quantity", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "quantity", "BIGINT", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "$quantity")));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("B2:expression_backfill_requires_ack:"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "widgets", "auditQuantity"));
        }
    }

    @Test
    void reviewableExpressionAutoAppliesOnlyWhenModeIsSafeAndReviewable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), quantity BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (1, 'alpha', 10)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        System.setProperty(AUTO_APPLY_MODE_PROPERTY, "safe-and-reviewable");

        // A VARCHAR target -- not BIGINT -- because no FunctionRegistry is ever wired for a
        // defaultExpression evaluation (functions are only usable in invariant expressions elsewhere),
        // so a function-call expression always falls through to ValueExpressionEvaluator's raw-text
        // fallback (a pre-existing quirk, unrelated to this package) rather than a real computed value.
        // A VARCHAR column accepts that fallback text; this test only asserts the auto-apply GATE
        // (REVIEWABLE needs the wider mode), not what the fallback value happens to be.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "quantity", "auditTag")),
                Map.of("widgets", List.of("auditTag")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "quantity", "BIGINT", "auditTag", "VARCHAR(200)")),
                Map.of("widgets", List.of("auditTag")),
                Map.of("widgets", List.of("auditTag")),
                Map.of("widgets", Map.of("auditTag", "riskyLookup(quantity)")));

        // REVIEWABLE only auto-applies once the operator explicitly opts into the wider mode -- no
        // ackToken is ever inserted in this test.
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "auditTag"));
            assertTrue(isNotNull(metadata, "widgets", "auditTag"));
        }
    }

    @Test
    void highRiskExpressionNeverAutoAppliesEvenUnderTheWidestMode() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), quantity BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (1, 'alpha', 10)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        // The widest mode this property accepts -- HIGH_RISK still must never auto-apply.
        System.setProperty(AUTO_APPLY_MODE_PROPERTY, "safe-and-reviewable");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "quantity", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "quantity", "BIGINT", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "scope.exists(x => x.status == 'A')")));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("B2:expression_backfill_requires_ack:"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "widgets", "auditQuantity"));
        }
    }

    @Test
    void acknowledgedExpressionDefaultBackfillsEachRowToItsOwnComputedValue() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), quantity BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (1, 'alpha', 10)");
            statement.execute("INSERT INTO widgets (id, name, quantity) VALUES (2, 'beta', 20)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "quantity", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "quantity", "BIGINT", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "$quantity")));

        // The operator's preview (the same REST surface SchemaImpactController exposes) and the
        // token they'd submit via the existing /acknowledge endpoint.
        List<ExpressionBackfillPreview.Item> preview = ExpressionBackfillPreview.preview(dataSource, manifest);
        assertEquals(1, preview.size());
        ExpressionBackfillPreview.Item item = preview.get(0);
        assertEquals(2, item.rowsAffected());
        assertTrue(item.distinctValues().containsAll(List.of("10", "20")), item.distinctValues().toString());
        assertFalse(item.hasFailures());
        String ackToken = ExpressionBackfillPreview.expectedToken(manifest.schemaFingerprint(), preview);

        PendingSchemaAcknowledgmentStore.insert(dataSource, manifest.schemaFingerprint(), ackToken, null, "test-operator");

        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "auditQuantity"));
            assertTrue(isNotNull(metadata, "widgets", "auditQuantity"));
            // Each row backfilled to its OWN quantity -- proving a per-row computed value, not one
            // literal shared by every row (the whole point of an expression default over a literal).
            assertEquals(10L, readLong(connection, "auditQuantity", 1));
            assertEquals(20L, readLong(connection, "auditQuantity", 2));
        }
    }

    @Test
    void acknowledgedExpressionDefaultWithAFailingRowRefusesAndAddsNoColumn() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'alpha')");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // "$missingField" references a field that does not exist on any row -- every row must fail.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestWithExpressionText(
                Map.of("widgets", List.of("id", "name", "auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "auditQuantity", "BIGINT")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", List.of("auditQuantity")),
                Map.of("widgets", Map.of("auditQuantity", "$missingField")));

        List<ExpressionBackfillPreview.Item> preview = ExpressionBackfillPreview.preview(dataSource, manifest);
        assertEquals(1, preview.size());
        assertTrue(preview.get(0).hasFailures(), "a reference to a field absent from every row must be reported as a failure");
        String ackToken = ExpressionBackfillPreview.expectedToken(manifest.schemaFingerprint(), preview);
        PendingSchemaAcknowledgmentStore.insert(dataSource, manifest.schemaFingerprint(), ackToken, null, "test-operator");

        // DoD (Move 9 B1): "rows where the expression fails are reported and BLOCK application" --
        // even acknowledged, a failing row refuses the whole pass rather than partially applying.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("auditQuantity"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("failed for row id"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "widgets", "auditQuantity"),
                    "a failing acknowledged expression backfill must never add the column at all");
        }
    }

    private static long readLong(Connection connection, String column, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM widgets WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestWithExpressionText(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, List<String>> businessTableExpressionDefaultColumns,
            Map<String, Map<String, String>> businessTableColumnDefaultExpressions) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:new",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                businessTableAdditiveColumns,
                businessTableColumnTypes,
                Map.of(),
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                businessTableRequiredColumns,
                Map.of(),
                businessTableExpressionDefaultColumns,
                Map.of(),
                List.of(),
                "NpdevManaged",
                Map.of(),
                Map.of(),
                businessTableColumnDefaultExpressions
        );
    }

    private static String readStatus(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM widgets WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static void seedStoredFingerprint(DataSource dataSource, String fingerprint) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_metadata "
                        + "(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_metadata (metadata_key, metadata_value, updated_at_ms) VALUES (?, ?, ?)")) {
                statement.setString(1, "schemaFingerprint");
                statement.setString(2, fingerprint);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    private record HistoryRow(String outcome) {
    }

    private static HistoryRow latestHistoryRow(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT outcome FROM npdev_schema_history ORDER BY applied_at_utc DESC")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected at least one npdev_schema_history row");
                return new HistoryRow(resultSet.getString(1));
            }
        }
    }

    private static boolean hasColumn(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isNotNull(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return "NO".equalsIgnoreCase(resultSet.getString("IS_NULLABLE"));
                    }
                }
            }
        }
        return false;
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, Map<String, String>> businessTableColumnDefaultLiterals,
            Map<String, List<String>> businessTableExpressionDefaultColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:new",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                businessTableAdditiveColumns,
                businessTableColumnTypes,
                Map.of(),
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                businessTableRequiredColumns,
                businessTableColumnDefaultLiterals,
                businessTableExpressionDefaultColumns,
                Map.of()
        );
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in an H2-specific compile-time dependency. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
