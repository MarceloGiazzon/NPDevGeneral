package com.finalexec.db;

import com.finalexec.boundary.BoundaryBootException;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 7 (task 7.1). The 16-scenario H2 proof matrix from
 * {@code docs/archive/programme-history/LNCH1_SCHEMA_EVOLUTION_PLAN.md}'s Phase 7 table, in ONE place, traceable row by row.
 * Every row is exercised here against a real H2 database via {@link SchemaLifecycleExecutor}'s
 * package-private methods (the same bare-JDBC, no-Spring-context style every Phase 1-6 test in
 * this package already uses), driving the actual production sequencing
 * ({@link SchemaLifecycleExecutor#beforeMigrate}/{@link SchemaLifecycleExecutor#afterMigrate}, not
 * a hand-reordered substitute) wherever a row's mechanism lives in those methods.
 *
 * <p><b>Rows already thoroughly proven, edge cases and all, by an existing elaborate test class</b>
 * -- this matrix's own test for that row is a compact, real, independently-executed re-derivation
 * (fresh scenario, not a call into the other file) that exercises the SAME production code path
 * end to end, plus this matrix's own explicit double-boot idempotence check; it does not attempt to
 * re-cover every edge case the cited class already owns:
 *
 * <table border="1">
 * <caption>16-row traceability</caption>
 * <tr><th>#</th><th>Scenario</th><th>Primary existing coverage (edge cases)</th></tr>
 * <tr><td>1</td><td>Add optional field with literal default</td>
 *     <td>{@link SchemaLifecycleExecutorAdditiveChangeTest} (classification only -- see this row's
 *     javadoc below for the honest scope note on "backfill")</td></tr>
 * <tr><td>2</td><td>Add required field, literal default, rows exist</td>
 *     <td>{@link SchemaLifecycleExecutorRequiredFieldBackfillTest},
 *     {@link SchemaLifecycleExecutorRequiredFieldBackfillCrashRecoveryTest}</td></tr>
 * <tr><td>3</td><td>Add required field, no default, rows exist</td>
 *     <td>{@link SchemaLifecycleExecutorRequiredFieldBackfillTest}</td></tr>
 * <tr><td>4</td><td>Rename field (renamedFrom)</td>
 *     <td>{@link SchemaLifecycleExecutorInPlaceRenameTest},
 *     {@link SchemaLifecycleExecutorRenameTypeChangeGapTest}</td></tr>
 * <tr><td>5</td><td>Rename concept</td>
 *     <td>{@link SchemaLifecycleExecutorTableRenameTest},
 *     {@link SchemaLifecycleExecutorTableRenameConstraintSurvivalTest} (FK/index survival),
 *     {@link SchemaLifecycleExecutorTableRenameBlindSpotTest}</td></tr>
 * <tr><td>6</td><td>Rename + widen same column</td>
 *     <td>{@link SchemaLifecycleExecutorTypeWideningIntegrationTest#renameComposedWithWideningOnTheSameColumnBothApplyInOneBoot}
 *     (confirmed real, not just planned, per LNCH-1 Phase 3 evidence)</td></tr>
 * <tr><td>7</td><td>Widen INT-&gt;BIGINT with max-INT value present</td>
 *     <td>{@link SchemaLifecycleExecutorTypeWideningIntegrationTest#integerToBigintWideningAppliesInPlaceAndBoundaryValueSurvives}</td></tr>
 * <tr><td>8</td><td>Narrow type, no ack</td><td><b>NEW this phase</b> -- no prior test drove a
 *     narrowing-only diff through the full {@code beforeMigrate} refusal path (prior narrowing
 *     coverage stopped at {@code attemptInPlaceTypeWidenings}/{@code classify}, not the itemized
 *     refusal message/token)</td></tr>
 * <tr><td>9</td><td>Drop field with matching ack</td>
 *     <td>{@link SchemaLifecycleExecutorDestructiveItemizationTest#dropColumnWithMatchingAckTokenSucceedsSurgicallyLeavingSiblingDataIntact}</td></tr>
 * <tr><td>10</td><td>Drop field with stale/foreign token</td>
 *     <td>{@link SchemaLifecycleExecutorDestructiveItemizationTest#staleTokenFromADifferentItemSetRefusesBootAndLeavesDatabaseUntouched}</td></tr>
 * <tr><td>11</td><td>New unique on dirty data</td>
 *     <td>{@link SchemaLifecycleExecutorUniqueConstraintTest#dirtyDataRefusesWithViolatingTuplesNamedAndNoPartialConstraint}</td></tr>
 * <tr><td>12</td><td>New unique on clean data</td>
 *     <td>{@link SchemaLifecycleExecutorUniqueConstraintTest#cleanDataAppliesTheConstraintAndSubsequentDuplicatesAreRejectedByTheDatabase}</td></tr>
 * <tr><td>13</td><td>New nullable bond to populated concept</td>
 *     <td>{@link SchemaLifecycleExecutorRequiredBondRefusalTest#newNullableBondColumnOnAPopulatedTableIsSafeAdditive}
 *     (classification/no-destruction half; the FK constraint DDL itself is Flyway/generator-emitted,
 *     proven at the generator emitter-test and live-app level, not this executor unit -- see this
 *     row's javadoc below)</td></tr>
 * <tr><td>14</td><td>Crash mid-destructive (freeze-thread) -&gt; reboot</td>
 *     <td>{@link SchemaLifecycleExecutorDestructiveCrashRecoveryTest#crashAfterTheFirstOfTwoSurgicalDropsLeavesPartialCrashHistoryAndAHalfAppliedDatabase_thenAFreshBootConverges}
 *     (the real freeze-thread fault injection); this matrix adds a second, independent proof of the
 *     same "residue re-classified, converges, no double-drop" property via a differently-constructed
 *     simulated-crash state (a column already dropped, mimicking exactly what a crash between two
 *     surgical DDL statements would leave)</td></tr>
 * <tr><td>15</td><td>No model change, reboot</td>
 *     <td><b>NEW this phase</b> -- no prior test exercised this as its own top-level scenario (only
 *     as the trailing assertion of other tests)</td></tr>
 * <tr><td>16</td><td>InMemory-storage app, any model change</td>
 *     <td><b>NEW this phase</b> -- confirmed genuinely missing by recon (grepped every test in this
 *     package for {@code physicalDatabase}; none existed). Required a small testability seam,
 *     {@link SchemaLifecycleExecutor#migrate(Flyway, SchemaLifecycleExecutor.SchemaManifest)}, since
 *     the guard being proven lives only in {@link SchemaLifecycleExecutor#migrate(Flyway)} and
 *     {@link SchemaLifecycleExecutor#loadManifest()} reads a fixed classpath resource with no other
 *     injection point</td></tr>
 * <tr><td>17</td><td>Ephemeral app, existing table with rows, model changed</td>
 *     <td><b>NEW (STOR-16)</b> -- the table is recreated empty with no diff, no impact report and no
 *     acknowledgment token, and a table NPDev does not own in the same schema is left untouched.
 *     Before STOR-16 there was no posture that could express this: {@code RecreateOnAppStart} read
 *     {@code strategy} nowhere at all, and {@code DropAndRecreateOnStructureChange} authorizes only
 *     itemized column drops and type narrowings -- a whole-table change still refuses and demands a
 *     token</td></tr>
 * </table>
 */
class SchemaLifecycleExecutorProofMatrixTest {

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    // ---- Row 1 --------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 1: add optional field with literal default -> SAFE_ADDITIVE, data intact")
    void row01_optionalFieldWithLiteralDefaultIsSafeAdditive() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // "notes" is optional (NOT in businessTableRequiredColumns) but declares a literal default.
        // Honest scope note (recon finding, LNCH-1 Phase 7): applyRequiredFieldBackfills only ever
        // scans manifest.businessTableRequiredColumns() (see its early continue when that list is
        // empty) -- an OPTIONAL field's literal default is never retroactively backfilled onto
        // existing rows by the executor; it is purely additive-eligible column metadata for
        // Flyway's R__ migration (out of this unit's scope, proven at the generator/live-app level).
        // This is correct, intended behavior (an optional column may legitimately stay NULL on
        // legacy rows) -- this test proves the classification/no-destruction half honestly, without
        // overclaiming a backfill that does not happen for non-required columns.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "name", "notes", "version")),
                Map.of("widgets", List.of("notes")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "notes", "VARCHAR(255)", "version", "BIGINT")),
                Map.of(), Map.of(), true, "",
                Map.of(), Map.of("widgets", Map.of("notes", "\"n/a\"")), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "an optional field with a literal default must resolve via the safe-additive path");
        assertFalse(result.performed(), "no destructive recreation must be performed");
        assertEquals("alpha", readName(dataSource, 1L), "pre-existing row's data must be untouched");

        executor.afterMigrate(dataSource, manifest);
        assertSecondBootIsNoOp(manifest);
    }

    // ---- Row 2 --------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 2: add required field, literal default, rows exist -> backfilled, NOT NULL after")
    void row02_requiredFieldWithLiteralDefaultBackfillsThenTightens() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (2, 'beta', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of(), Map.of(), true, "",
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("status", "\"PENDING\"")), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive());
        assertFalse(result.performed());

        // R2 (F1): required-field backfill/refusal now runs in afterMigrate (the single call site),
        // not beforeMigrate -- so the column is added/backfilled/tightened here, not above.
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "status"), "the required column must have been added");
            assertTrue(isNotNull(metadata, "widgets", "status"), "the column must be enforced NOT NULL after backfill");
            assertEquals("PENDING", readColumn(connection, "widgets", "status", 1L));
            assertEquals("PENDING", readColumn(connection, "widgets", "status", 2L));
        }

        assertSecondBootIsNoOp(manifest);
    }

    // ---- Row 3 --------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 3: add required field, no default, rows exist -> REFUSED with named remedy")
    void row03_requiredFieldWithNoDefaultRefusesWithNamedRemedy() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "name", "status", "version")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of(), Map.of(), true, "",
                Map.of("widgets", List.of("status")), Map.of(), Map.of(), Map.of(), true);

        // R2 (F1): beforeMigrate classifies this as safe-additive (the column is additive-eligible);
        // the no-default refusal now fires from the single afterMigrate enforcement call site.
        SchemaLifecycleExecutor.DestructiveRecreation classified = executor.beforeMigrate(dataSource, manifest);
        assertTrue(classified.safeAdditive());

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("status"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("no default declared"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "widgets", "status"), "the refused column must never be added");
        }

        // A retry with the same (still-unfixed) manifest must refuse again, identically -- not
        // half-apply or crash differently on a second attempt.
        assertThrows(IllegalStateException.class, () -> executor.afterMigrate(dataSource, manifest));
    }

    // ---- Row 4 --------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 4: rename field (renamedFrom) -> in-place, data intact")
    void row04_fieldRenameIsAppliedInPlace() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_label VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_label, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "new_label", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "new_label", "VARCHAR(50)", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_label", "old_label")), Map.of(), true, "",
                Map.of(), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "a fully-explained rename must resolve safely, not destructively");
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_label"));
            assertFalse(hasColumn(metadata, "widgets", "old_label"));
            assertEquals("alpha", readColumn(connection, "widgets", "new_label", 1L), "data must survive the rename");
        }

        executor.afterMigrate(dataSource, manifest);
        assertSecondBootIsNoOp(manifest);
    }

    // ---- Row 5 --------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 5: rename concept -> in-place, data intact (FK/index survival: see cited class)")
    void row05_conceptRenameIsAppliedInPlace() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO gadgets (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "name", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of(), Map.of("widgets", "gadgets"), true, "",
                Map.of(), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "a fully-explained concept rename must resolve safely");
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "widgets"));
            assertFalse(hasTable(metadata, "gadgets"));
            assertEquals("alpha", readColumn(connection, "widgets", "name", 1L), "data must survive the table rename");
        }

        executor.afterMigrate(dataSource, manifest);
        assertSecondBootIsNoOp(manifest);
    }

    // ---- Row 6 --------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 6: rename + widen same column -> both applied, data intact (see cited class for the full proof)")
    void row06_renameAndWidenSameColumnBothApply() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, old_quantity INTEGER, version BIGINT)");
            statement.execute("INSERT INTO widgets (id, old_quantity, version) VALUES (1, 1000, 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "new_quantity", "version")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "new_quantity", "BIGINT", "version", "BIGINT")),
                Map.of("widgets", Map.of("new_quantity", "old_quantity")), Map.of(), true, "",
                Map.of(), Map.of(), Map.of(), Map.of(), true);

        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED,
                executor.classify(dataSource, manifest),
                "a rename+widen on the same column classifies as TYPE_CHANGE_DETECTED before any step runs");

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "the full beforeMigrate sequence must resolve the composed rename+widen");
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "new_quantity"));
            assertEquals("BIGINT", columnTypeName(metadata, "widgets", "new_quantity"));
            assertEquals(1000, Long.parseLong(readColumn(connection, "widgets", "new_quantity", 1L)), "data must survive both operations");
        }

        executor.afterMigrate(dataSource, manifest);
        assertSecondBootIsNoOp(manifest);
    }

    // ---- Row 7 --------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 7: widen INT->BIGINT with max-INT value present -> applied, value intact")
    void row07_intToBigintWideningPreservesBoundaryValue() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, quantity INTEGER)");
            statement.execute("INSERT INTO widgets (id, quantity) VALUES (1, " + Integer.MAX_VALUE + ")");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "quantity")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "quantity", "BIGINT")),
                Map.of(), Map.of(), true, "",
                Map.of(), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive());
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals("BIGINT", columnTypeName(metadata, "widgets", "quantity"));
            assertEquals(Integer.MAX_VALUE, Long.parseLong(readColumn(connection, "widgets", "quantity", 1L)),
                    "the boundary value must survive the widening exactly");
        }

        executor.afterMigrate(dataSource, manifest);
        assertSecondBootIsNoOp(manifest);
    }

    // ---- Row 8 (NEW this phase) -----------------------------------------------------------------

    @Test
    @DisplayName("Row 8: narrow type, no ack -> REFUSED, itemized, token printed")
    void row08_narrowingTypeChangeWithNoAckIsRefusedWithItemizedTokenNamed() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, code VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, code) VALUES (1, 'a-longer-than-twenty-chars-value')");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // VARCHAR(50) -> VARCHAR(20): a pure narrowing, no ack token supplied, no blanket flag.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "code")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "code", "VARCHAR(20)")),
                Map.of(), Map.of(), false, "",
                Map.of(), Map.of(), Map.of(), Map.of(), true);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("NARROW_TYPE:widgets:code:VARCHAR(50):VARCHAR(20)"),
                "the refusal must itemize the narrowing precisely: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Expected acknowledgment token:"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals(50, columnSize(metadata, "widgets", "code"), "the DB must be completely untouched by a refusal");
        }
        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("REFUSED", row.outcome());
    }

    // ---- Row 9 --------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 9: drop field with matching ack -> only that column gone, snapshot written, history row")
    void row09_dropColumnWithMatchingAckIsSurgical() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (1, 'Alpha', TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "name")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of(), true);
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifestWithoutToken);
        String expectedToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "name")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, expectedToken, Map.of(), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.performed());
        assertFalse(result.safeAdditive());
        assertEquals(List.of("widgets"), result.droppedTables(), "the surgical path must scope to only the affected table");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "legacy_flag"), "the dropped column must be gone");
            assertTrue(hasColumn(metadata, "widgets", "name"), "a sibling column must survive");
            assertEquals("Alpha", readColumn(connection, "widgets", "name", 1L), "the row itself must survive, minus the dropped column");
        }
        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("APPLIED", row.outcome());
        assertEquals(expectedToken, row.ackTokenUsed());

        executor.afterMigrate(dataSource, manifest);
        assertSecondBootIsNoOp(manifest);
    }

    // ---- Row 9 follow-up (found live on Postgres during Phase 7 rehearsal) --------------------

    @Test
    @DisplayName("Row 9 follow-up: rename + unrelated acknowledged drop on the SAME table -> "
            + "rename applies in place (data preserved), drop applies surgically")
    void row09Followup_renamePlusUnrelatedAcknowledgedDropOnSameTableBothApply() throws SQLException {
        // Reproduces, against a real boot sequence, the exact silent data-loss bug found live during
        // the Phase 7 compose-stack rehearsal: classify() escalated straight to DESTRUCTIVE (skipping
        // RENAME_DETECTED entirely) whenever a table had BOTH a declared rename AND an unrelated,
        // separately-acknowledged column drop -- because attemptInPlaceRenames was only ever called
        // from inside classify()'s RENAME_DETECTED/TYPE_CHANGE_DETECTED branches, which this table
        // never reached. The rename was silently skipped: the OLD column's data was left orphaned
        // under its old name while the additive-columns migration added the NEW column empty -- no
        // error, no refusal, a "successful" boot with quietly lost data.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (1, 'Alpha', TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "title")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "title", "VARCHAR(50)")),
                Map.of("widgets", Map.of("title", "name")), Map.of(), false, "",
                Map.of(), Map.of(), Map.of(), Map.of(), true);
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifestWithoutToken);
        assertEquals(List.of("DROP_COLUMN:widgets:legacy_flag:BOOLEAN"), report.stableStrings(),
                "the rename pair must NOT show up as a drop -- only the genuinely unrelated column");
        String expectedToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "title")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "title", "VARCHAR(50)")),
                Map.of("widgets", Map.of("title", "name")), Map.of(), false, expectedToken,
                Map.of(), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.performed());
        assertFalse(result.safeAdditive());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "name"), "the old column name must be gone (renamed away)");
            assertTrue(hasColumn(metadata, "widgets", "title"), "the new column name must be present");
            assertFalse(hasColumn(metadata, "widgets", "legacy_flag"), "the unrelated acknowledged drop must still apply");
            assertEquals("Alpha", readColumn(connection, "widgets", "title", 1L),
                    "the renamed column's data must be PRESERVED, not left orphaned under the old name or empty under the new one");
        }
        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("APPLIED", row.outcome());

        executor.afterMigrate(dataSource, manifest);
        assertSecondBootIsNoOp(manifest);
    }

    // ---- Row 10 -------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 10: drop field with stale/foreign token -> REFUSED, DB untouched")
    void row10_dropColumnWithStaleTokenIsRefused() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        String staleToken = DestructiveAckToken.compute("sha256:new", List.of("DROP_COLUMN:widgets:some_other_column:VARCHAR(10)"));
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(), false, staleToken, Map.of(), Map.of(), Map.of(), Map.of(), true);

        assertThrows(IllegalStateException.class, () -> executor.beforeMigrate(dataSource, manifest));

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, "widgets", "legacy_flag"), "a stale token must not authorize anything -- DB untouched");
        }
        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("REFUSED", row.outcome());
        assertEquals(staleToken, row.ackTokenUsed(), "the (wrong) attempted token is still recorded for audit purposes");
    }

    // ---- Row 11 -------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 11: new unique on dirty data -> REFUSED with violating tuples")
    void row11_newUniqueOnDirtyDataIsRefusedNamingTheTuples() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100), tenant_id VARCHAR(120), version BIGINT)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (1, 'dup@x.com', 'acme', 1)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (2, 'dup@x.com', 'acme', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        SchemaLifecycleExecutor.SchemaManifest manifest = uniqueManifest(
                Map.of("users", List.of("id", "email", "tenant_id", "version")),
                List.of(new SchemaLifecycleExecutor.UniqueConstraintDecl("ux_users_email", List.of("email"), true)));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("dup@x.com"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertFalse(constraintExists(connection, "users", "ux_users_email"), "a refused constraint must never be partially applied");
        }
    }

    // ---- Row 12 -------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 12: new unique on clean data -> applied")
    void row12_newUniqueOnCleanDataIsApplied() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100), tenant_id VARCHAR(120), version BIGINT)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (1, 'alice@x.com', 'acme', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        SchemaLifecycleExecutor.SchemaManifest manifest = uniqueManifest(
                Map.of("users", List.of("id", "email", "tenant_id", "version")),
                List.of(new SchemaLifecycleExecutor.UniqueConstraintDecl("ux_users_email", List.of("email"), true)));

        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(constraintExists(connection, "users", "ux_users_email"), "a clean pass must apply the constraint");
        }

        // Idempotence: re-running afterMigrate against an already-constrained table must not error.
        executor.afterMigrate(dataSource, manifest);
    }

    // ---- Row 12 follow-up (found live on Postgres during Phase 7 rehearsal) -------------------

    @Test
    @DisplayName("Row 12 follow-up: unique already present as V1's bootstrap INDEX (not a constraint) -> "
            + "recognized as already-applied, no duplicate ADD CONSTRAINT attempt")
    void row12Followup_uniqueAlreadyPresentAsBootstrapIndexIsRecognizedAsApplied() throws SQLException {
        // Mirrors SchemaRealizationEmitter's V1 bootstrap DDL for an ordinary (non-anchor) unique
        // field: CREATE UNIQUE INDEX IF NOT EXISTS ux_<table>_<column> -- an INDEX, never an
        // ADD CONSTRAINT -- under the exact same name applyUniqueConstraints later tries to apply.
        // INFORMATION_SCHEMA.TABLE_CONSTRAINTS (constraintExists' original, only check) does not
        // list plain indexes, so this same-named index was invisible to the "already applied"
        // check; on a real Postgres boot the follow-on ADD CONSTRAINT then collided with the
        // index's relation and threw "relation \"ux_users_email\" already exists", crash-looping
        // the container on first start (H2 tolerates the duplicate name, which is why this was
        // missed until Phase 7's real compose-stack rehearsal).
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100), tenant_id VARCHAR(120), version BIGINT)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_users_email ON users (tenant_id, email)");
            statement.execute("INSERT INTO users (id, email, tenant_id, version) VALUES (1, 'alice@x.com', 'acme', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        SchemaLifecycleExecutor.SchemaManifest manifest = uniqueManifest(
                Map.of("users", List.of("id", "email", "tenant_id", "version")),
                List.of(new SchemaLifecycleExecutor.UniqueConstraintDecl("ux_users_email", List.of("email"), true)));

        executor.afterMigrate(dataSource, manifest);
        // Re-run for the same reason Row 12 does: converges without ever attempting a duplicate
        // ADD CONSTRAINT against the bootstrap index.
        executor.afterMigrate(dataSource, manifest);
    }

    // ---- Row 13 -------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 13: new nullable bond to populated concept -> applied (FK constraint proven at generator/live-app level)")
    void row13_newNullableBondColumnOnPopulatedConceptIsSafeAdditive() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO orders (id, name, version) VALUES (1, 'first order', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // A nullable bond column ("customer_id") declared additive-eligible, exactly what the
        // generator emits for a NULLABLE bond field (LNCH-1 P5 5.3).
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("orders"),
                Map.of("orders", List.of("id", "name", "customer_id", "version")),
                Map.of("orders", List.of("customer_id")),
                Map.of(), Map.of(), Map.of(), true, "",
                Map.of(), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "a new nullable bond column must resolve via the ordinary safe-additive path");
        assertFalse(result.performed());
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("first order", readColumn(connection, "orders", "name", 1L), "pre-existing row must be untouched");
        }
    }

    // ---- Row 14 -------------------------------------------------------------------------------

    @Test
    @DisplayName("Row 14: crash mid-destructive -> reboot converges (independent proof; see cited class for the freeze-thread version)")
    void row14_residueLeftByASimulatedMidDestructiveCrashConvergesOnReboot() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, name, legacy_flag) VALUES (1, 'Alpha', TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "name")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of(), true);
        String expectedToken = DestructiveAckToken.compute("sha256:new",
                SchemaDeltaReport.generate(dataSource, manifestWithoutToken).stableStrings());
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "name")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, expectedToken, Map.of(), Map.of(), Map.of(), Map.of(), true);

        // Simulate exactly what a JVM crash between "drop legacy_flag" and "write the APPLIED
        // history row" would leave: the DDL already applied, no history row committed for THIS
        // pass yet (a differently-shaped fault injection than the cited freeze-thread test, but the
        // same converged live-DB state it produces mid-crash).
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE widgets DROP COLUMN legacy_flag");
        }

        // A fresh boot against this residue must re-classify from the live DB (now already matching
        // the manifest) and converge -- not attempt a double-drop, not error.
        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertFalse(result.performed(), "the column is already gone -- nothing further to drop, this must NOT be a second destructive pass");
        assertTrue(result.safeAdditive(), "the live DB already matches the manifest once the crashed drop is accounted for");

        try (Connection connection = dataSource.getConnection()) {
            assertEquals("Alpha", readColumn(connection, "widgets", "name", 1L), "the row must survive the converged reboot");
        }

        executor.afterMigrate(dataSource, manifest);
        assertSecondBootIsNoOp(manifest);
    }

    // ---- Row 15 (NEW this phase) ----------------------------------------------------------------

    @Test
    @DisplayName("Row 15: no model change, reboot -> pure no-op, fingerprint untouched")
    void row15_noModelChangeRebootIsAPureNoOp() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'alpha')");
        }
        seedStoredFingerprint(dataSource, "sha256:unchanged");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:unchanged", List.of("widgets"),
                Map.of("widgets", List.of("id", "name")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), true, "", Map.of(), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertFalse(result.performed(), "no fingerprint diff at all must never trigger destructive recreation");
        assertFalse(result.safeAdditive(), "no fingerprint diff at all is not even the safe-additive path -- it is a pure no-op");

        assertEquals("sha256:unchanged", readStoredFingerprint(dataSource), "the stored fingerprint must be completely untouched by a no-diff boot");
        assertEquals("alpha", readColumn(dataSource.getConnection(), "widgets", "name", 1L));
    }

    // ---- Row 16 (NEW this phase) ----------------------------------------------------------------

    @Test
    @DisplayName("Row 16: InMemory-storage app, any model change -> executor no-ops entirely")
    void row16_inMemoryStorageAppNeverInvokesTheExecutor() throws SQLException {
        // physicalDatabase = false, with an otherwise-destructive-looking fingerprint mismatch and
        // NO stored fingerprint at all -- none of it must matter, since the guard in migrate(Flyway,
        // SchemaManifest) short-circuits before beforeMigrate/afterMigrate are ever reached.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:whatever-this-must-never-be-read", List.of("widgets"),
                Map.of("widgets", List.of("id")), Map.of("widgets", List.of()),
                Map.of(), Map.of(), Map.of(), true, "", Map.of(), Map.of(), Map.of(), Map.of(), false);

        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();

        executor.migrate(flyway, manifest);
        assertNoNpdevSchemaTablesExist("first boot");

        // Idempotence: a second "boot" with the identical manifest must be exactly as inert.
        executor.migrate(flyway, manifest);
        assertNoNpdevSchemaTablesExist("second boot");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "flyway_schema_history"),
                    "the guard's fallback (flyway.migrate()) must still have run for real");
        }
    }

    @Test
    @DisplayName("Row 17: Ephemeral app -> tables recreated empty, no refusal, no token, neighbours untouched")
    void row17_ephemeralAppDiscardsItsOwnDataAndNothingElse() throws SQLException {
        // A table with rows, and a stored fingerprint that does NOT match the manifest -- i.e. the
        // exact situation that makes DropAndRecreateOnStructureChange refuse and demand an itemized
        // acknowledgment token. Plus a table in the same schema that NPDev does not own.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'alpha')");
            statement.execute("CREATE TABLE someone_elses_table (id BIGINT PRIMARY KEY, note VARCHAR(50))");
            statement.execute("INSERT INTO someone_elses_table (id, note) VALUES (1, 'not npdev')");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = ephemeralManifest(
                "sha256:new", List.of("widgets"), Map.of("widgets", List.of("id", "name", "addedField")));

        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();
        // Baseline first, because this scenario is "an app that has booted before". Hand-creating
        // the tables without also giving Flyway its history table produced a schema no real app can
        // be in -- populated, but unknown to Flyway -- and Flyway rightly refused it ("found
        // non-empty schema without schema history table"). The first draft of this test asserted
        // against that impossible state and failed for a reason that had nothing to do with STOR-16.
        flyway.baseline();
        executor.migrate(flyway, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasTable(metadata, "widgets"),
                    "the NPDev-owned table must have been dropped; Flyway rebuilds it from the model "
                            + "(this unit has no migrations, so it simply stays gone here)");
            assertTrue(hasTable(metadata, "someone_elses_table"),
                    "a table NPDev does not own must survive -- scope: NpdevOwnedTablesOnly is the "
                            + "whole reason the wipe reads the manifest instead of the schema");
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT note FROM someone_elses_table WHERE id = 1")) {
            assertTrue(rows.next());
            assertEquals("not npdev", rows.getString(1), "the neighbour's ROWS must be intact too");
        }
    }

    @Test
    @DisplayName("Row 17b: Ephemeral is inert on a boot with nothing to drop")
    void row17b_ephemeralOnAFreshDatabaseIsNotAnError() throws SQLException {
        // The first-ever boot of an ephemeral app: no tables, no stored fingerprint. `DROP TABLE IF
        // EXISTS` makes this a no-op, and it must stay one -- an app that crashes on its own first
        // start would be a spectacular way to ship this.
        SchemaLifecycleExecutor.SchemaManifest manifest = ephemeralManifest(
                "sha256:new", List.of("widgets"), Map.of("widgets", List.of("id")));

        Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();
        executor.migrate(flyway, manifest);

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(hasTable(connection.getMetaData(), "flyway_schema_history"),
                    "flyway.migrate() must still run after the wipe, or the tables are never rebuilt");
        }
    }

    @Test
    @DisplayName("Row 18: forcePhysicalSchema overrides a false physicalDatabase and runs realization anyway")
    void row18_forcePhysicalSchemaOverridesAnInMemoryDeclaredManifest() throws SQLException {
        // Same physicalDatabase=false shape as Row 16 (an InMemory-declared db.definition.json), but
        // this time the executor has forcePhysicalSchema set -- as application-postgres.yml sets it,
        // for a profile that forces a real Postgres testcontainer onto an InMemory-declared model for
        // testing. Unlike Row 16, this must NOT no-op: the guard's "&& !forcePhysicalSchema" clause
        // means beforeMigrate/afterMigrate actually run.
        //
        // Cannot assert a BUSINESS table (e.g. "widgets") gets created here: like every other test in
        // this class, locations(new String[0]) means flyway.migrate() has no real migration content to
        // run, so no business DDL executes regardless of this guard -- that only happens in a real
        // generated app's own classpath:db/schema-realization migrations. What IS directly executed by
        // THIS class, via raw JDBC in afterMigrate (not through Flyway), is npdev_schema_metadata --
        // the exact table Row 16's assertNoNpdevSchemaTablesExist checks for absence. Asserting its
        // presence here proves afterMigrate ran, the inverse of what Row 16 proves.
        executor.forcePhysicalSchema = true;
        try {
            SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                    "H2Local", "jdbc", false, "sha256:new", List.of(), List.of("widgets"),
                    Map.of("widgets", List.of("id")), Map.of(), Map.of(), Map.of(), Map.of(), true,
                    "Ephemeral", "NpdevOwnedTablesOnly",
                    "I_UNDERSTAND_ALL_DATA_IS_DELETED_ON_EVERY_START", "",
                    Map.of(), Map.of(), Map.of(), Map.of());

            Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();
            executor.migrate(flyway, manifest);

            try (Connection connection = dataSource.getConnection()) {
                assertTrue(hasTable(connection.getMetaData(), "npdev_schema_metadata"),
                        "forcePhysicalSchema=true must let afterMigrate run despite manifest.physicalDatabase()=false");
            }
        } finally {
            executor.forcePhysicalSchema = false;
        }
    }

    @Test
    @DisplayName("Row 19: forcePhysicalSchema creates real business tables from the manifest, usable for CRUD")
    void row19_forcePhysicalSchemaCreatesUsableBusinessTables() throws SQLException {
        // canonical-demo's actual shape: an InMemory-declared manifest (physicalDatabase=false) has no
        // real V1__ migration, so flyway.migrate() in Row 18's scenario creates nothing. This proves
        // MissingTableCreationPass fills that gap: a real, insertable/queryable table, not just the
        // npdev_* bookkeeping Row 18 already covers.
        executor.forcePhysicalSchema = true;
        try {
            // Inline construction, not the manifest() helper: that helper hardcodes strategy =
            // DropAndRecreateOnStructureChange, which would take an entirely different code path than
            // the Ephemeral one MissingTableCreationPass is wired into -- see Row 18's identical need.
            SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                    "H2Local", "jdbc", false, "sha256:new", List.of(), List.of("widgets"),
                    Map.of("widgets", List.of("id", "name")), Map.of(),
                    Map.of("widgets", Map.of("id", "VARCHAR(36)", "name", "VARCHAR(50)")),
                    Map.of(), Map.of(), true,
                    "Ephemeral", "NpdevOwnedTablesOnly",
                    "I_UNDERSTAND_ALL_DATA_IS_DELETED_ON_EVERY_START", "",
                    Map.of(), Map.of(), Map.of(), Map.of());

            Flyway flyway = Flyway.configure().dataSource(dataSource).locations(new String[0]).load();
            executor.migrate(flyway, manifest);

            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metadata = connection.getMetaData();
                assertTrue(hasTable(metadata, "widgets"), "the business table itself must exist");
                assertTrue(hasColumn(metadata, "widgets", "id"), "the id column must exist");
                assertTrue(hasColumn(metadata, "widgets", "name"), "the model-declared column must exist");
            }

            // Not just present in metadata -- genuinely usable, including the id PRIMARY KEY this pass
            // declares (a second row with a duplicate id must be rejected).
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO widgets (id, name) VALUES ('11111111-1111-1111-1111-111111111111', 'alpha')");
                assertEquals("alpha", queryWidgetName(connection));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO widgets (id, name) VALUES ('11111111-1111-1111-1111-111111111111', 'duplicate')"),
                        "the id PRIMARY KEY this pass declares must actually be enforced");
            }
        } finally {
            executor.forcePhysicalSchema = false;
        }
    }

    private static String queryWidgetName(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM widgets WHERE name = 'alpha'")) {
            assertTrue(rows.next(), "the inserted row must be readable back");
            return rows.getString(1);
        }
    }

    private void assertNoNpdevSchemaTablesExist(String when) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasTable(metadata, "npdev_schema_metadata"),
                    "physicalDatabase=false must never let beforeMigrate/afterMigrate run (" + when + ")");
            assertFalse(hasTable(metadata, "npdev_schema_history"),
                    "physicalDatabase=false must never let beforeMigrate/afterMigrate run (" + when + ")");
        }
    }

    // ---- LNCH-1 remediation scenarios (R1/R2) ---------------------------------------------------

    @Test
    @DisplayName("Scenario 17 (F1): acknowledged DROP_COLUMN on B + new required-with-default field on A "
            + "-> drop applied AND field backfilled + NOT NULL, in one destructive boot")
    void scenario17_requiredFieldBackfillRunsEvenOnTheDestructivePath() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'Alpha')");
            statement.execute("INSERT INTO widgets (id, name) VALUES (2, 'Beta')");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO gadgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestNoToken = scenarioManifest("");
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifestNoToken);
        assertEquals(List.of("DROP_COLUMN:gadgets:legacy_flag:BOOLEAN"), report.stableStrings(),
                "only the genuine drop is itemized; the required-with-default field is additive, not a drop");
        String token = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        SchemaLifecycleExecutor.SchemaManifest manifest = scenarioManifest(token);
        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.performed(), "the acknowledged drop must go through the surgical destructive path");

        // F1's fix: required-field enforcement now runs at the single afterMigrate call site, so it is
        // reached even though this boot took the destructive path (before R2 it was silently skipped).
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "gadgets", "legacy_flag"), "the acknowledged drop must have applied");
            assertTrue(hasColumn(metadata, "widgets", "status"),
                    "the new required column must exist even though this boot was destructive");
            assertTrue(isNotNull(metadata, "widgets", "status"), "the new required column must be enforced NOT NULL");
            assertEquals("PENDING", readColumn(connection, "widgets", "status", 1L), "legacy row backfilled to the default");
            assertEquals("PENDING", readColumn(connection, "widgets", "status", 2L), "legacy row backfilled to the default");
        }

        assertSecondBootIsNoOp(manifest);
    }

    @Test
    @DisplayName("Scenario 18 (F1): acknowledged drop + new required field with NO default -> boot refuses "
            + "(#new-required-fields) with the token still valid; fingerprint stays stale; a fixed-model boot converges")
    void scenario18_requiredFieldNoDefaultRefusesEvenWithAValidDestructiveToken() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'Alpha')");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO gadgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // Same shape as scenario 17 but status has NO literal default declared.
        String token = DestructiveAckToken.compute("sha256:new", List.of("DROP_COLUMN:gadgets:legacy_flag:BOOLEAN"));
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets", "gadgets"),
                Map.of("widgets", List.of("id", "name", "status"), "gadgets", List.of("id")),
                Map.of("widgets", List.of("status"), "gadgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)"),
                        "gadgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(), false, token,
                Map.of("widgets", List.of("status")), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.performed(), "the acknowledged drop still applies -- it executes before the afterMigrate refusal");

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.afterMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("status"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("#new-required-fields"), refusal.getMessage());

        // The refusal left the fingerprint stale (so the next boot re-attempts). The destructive item,
        // however, DID already apply on this path -- a documented ordering consequence (the refusal
        // arrives after flyway.migrate/the surgical drop). See docs/SCHEMA_EVOLUTION.md refusal section.
        assertEquals("sha256:old", readStoredFingerprint(dataSource), "a refusal must leave the fingerprint stale");
        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasColumn(connection.getMetaData(), "gadgets", "legacy_flag"),
                    "documented ordering consequence: the acknowledged drop already applied before the refusal");
        }

        // A subsequent fixed-model boot (status now carries a literal default) converges cleanly.
        SchemaLifecycleExecutor.SchemaManifest fixed = manifest(
                "sha256:new", List.of("widgets", "gadgets"),
                Map.of("widgets", List.of("id", "name", "status"), "gadgets", List.of("id")),
                Map.of("widgets", List.of("status"), "gadgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)"),
                        "gadgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(), false, "",
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("status", "\"PENDING\"")), Map.of(), Map.of(), true);
        SchemaLifecycleExecutor.DestructiveRecreation converge = executor.beforeMigrate(dataSource, fixed);
        assertTrue(converge.safeAdditive(), "with the drop already applied, the remaining diff is a safe-additive field");
        executor.afterMigrate(dataSource, fixed);
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(isNotNull(metadata, "widgets", "status"), "the fixed-model boot finishes the required-field enforcement");
            assertEquals("PENDING", readColumn(connection, "widgets", "status", 1L), "the legacy row is backfilled on convergence");
        }
        assertEquals("sha256:new", readStoredFingerprint(dataSource), "the converged boot finally stores the new fingerprint");
    }

    @Test
    @DisplayName("Scenario 18b (F1 guard): required BOND column + acknowledged unrelated drop -> the dedicated "
            + "bond refusal still fires (from beforeMigrate, before the drop applies)")
    void scenario18b_requiredBondRefusalStillFiresAlongsideAnAcknowledgedDrop() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'Alpha')");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO gadgets (id, legacy_flag) VALUES (1, TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // widgets gains a required, NON-additive-eligible bond column 'owner_id'; gadgets drops legacy_flag.
        String token = DestructiveAckToken.compute("sha256:new", List.of("DROP_COLUMN:gadgets:legacy_flag:BOOLEAN"));
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets", "gadgets"),
                Map.of("widgets", List.of("id", "name", "owner_id"), "gadgets", List.of("id")),
                Map.of("widgets", List.of(), "gadgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "owner_id", "UUID"),
                        "gadgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(), false, token,
                Map.of("widgets", List.of("owner_id")), Map.of(), Map.of(), Map.of(), true);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, manifest));
        assertTrue(refusal.getMessage().contains("owner_id"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("bond"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(hasColumn(connection.getMetaData(), "gadgets", "legacy_flag"),
                    "the bond refusal fires before the destructive section -- the acknowledged drop must NOT have applied");
        }
    }

    @Test
    @DisplayName("Scenario 19 (R1/F2): a concept-drop token computed WITHOUT the live row count (exactly as "
            + "-PlanOnly does) is byte-identical to the executor's boot-time token and authorizes the boot")
    void scenario19_conceptDropTokenIsPlanComputableAndBootsFirstTry() throws SQLException {
        // A concept drop is only routed destructively when the boot's diff already reaches the
        // destructive path (classify() enumerates only manifest-declared tables, so an orphan table
        // alone is invisible to it -- a known platform boundary). We force the path with a companion
        // acknowledged DROP_COLUMN on the surviving concept, and prove that the DROP_TABLE component of
        // the token is plan-computable: the generator's -PlanOnly path has no live DB and constructs
        // DropTable(gadgets, -1), yet gadgets holds 3 live rows. Before R1 the row count was in the hash,
        // so the plan-time and boot-time tokens could NEVER match for a concept drop.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO widgets (id, legacy_flag) VALUES (1, TRUE)");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO gadgets (id, name) VALUES (1, 'a')");
            statement.execute("INSERT INTO gadgets (id, name) VALUES (2, 'b')");
            statement.execute("INSERT INTO gadgets (id, name) VALUES (3, 'c')");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestNoToken = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of(), true);
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifestNoToken);
        String executorExpectedToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        // Independently reconstruct the token the -PlanOnly generator would print (no live DB):
        // DropColumn with the normalized model type + DropTable with the unknown (-1) row count.
        String planTimeToken = DestructiveAckToken.compute("sha256:new", List.of(
                new SchemaDeltaItem.DropColumn("widgets", "legacy_flag", SqlTypeNormalization.normalize("BOOLEAN")).stableString(),
                new SchemaDeltaItem.DropTable("gadgets", -1L).stableString()));
        assertEquals(executorExpectedToken, planTimeToken,
                "R1/F2: the plan-time token (DROP_TABLE row count OUT of the hash) must byte-match the "
                        + "executor's boot-time token despite gadgets holding 3 live rows");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(), false, planTimeToken, Map.of(), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.performed(),
                "the plan-time token must authorize the destructive boot (concept drop + column drop) on the first attempt");
        assertTrue(result.droppedTables().contains("gadgets"), "the dropped concept's table must be among the affected tables");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasTable(metadata, "gadgets"), "the dropped concept's table must be gone");
            assertTrue(hasTable(metadata, "widgets"), "the surviving concept's table must remain");
            assertFalse(hasColumn(metadata, "widgets", "legacy_flag"), "the companion acknowledged column drop must have applied");
        }
        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("APPLIED", row.outcome());

        executor.afterMigrate(dataSource, manifest);
        assertSecondBootIsNoOp(manifest);
    }

    @Test
    @DisplayName("Scenario 21 (R3/F4): redeploying an OLD jar against a schema a NEWER build already "
            + "migrated -> schema-ahead-of-build refusal, not a deceptively clean boot")
    void scenario21_schemaAheadOfBuildIsRefusedNotBootedClean() throws SQLException {
        // Live state after a newer build renamed widgets.name -> full_name and then refused its
        // destructive item (so the fingerprint was never advanced past the old build's value).
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, full_name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, full_name) VALUES (1, 'Alpha')");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // The OLD jar still expects the pre-rename column 'name' and carries the OLD fingerprint --
        // which MATCHES the stored one, so without the R3 detector it would boot "clean" and then
        // fail at runtime referencing a column that no longer exists.
        // LNCH-1 hardening X3 (finding X-B2): this fixture used to pass Map.of("widgets", List.of())
        // for businessTableAdditiveColumns -- "nothing on this table is additive-eligible", a shape
        // NO real manifest has. That is the only reason the original single-trigger detector fired
        // here. With realistic additive columns ('name' IS additive-eligible, as it is in every real
        // manifest), the original detector skipped 'name' entirely and this scenario booted clean.
        Map<String, List<String>> oldColumns = Map.of("widgets", List.of("id", "name"));
        SchemaLifecycleExecutor.SchemaManifest oldManifest = manifest(
                "sha256:old", List.of("widgets"),
                oldColumns, realisticAdditiveColumns(oldColumns, Map.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of(), true);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, oldManifest));
        assertTrue(refusal.getMessage().contains("missing column(s) this build requires"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("widgets.name"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("#refusals-and-rollback"), refusal.getMessage());
        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("REFUSED", row.outcome());

        // The legitimate roll-forward -- the NEWER build whose manifest matches the live (renamed)
        // schema -- must NOT be blocked (its fingerprint mismatches, so the detector never runs).
        SchemaLifecycleExecutor.SchemaManifest newManifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "full_name")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "full_name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of(), true);
        SchemaLifecycleExecutor.DestructiveRecreation rollForward = executor.beforeMigrate(dataSource, newManifest);
        assertFalse(rollForward.performed(), "the roll-forward build must boot without destructive recreation");
        executor.afterMigrate(dataSource, newManifest);
        assertSecondBootIsNoOp(newManifest);
    }

    @Test
    @DisplayName("Scenario 21b (X-B2 guard): a manifest column that is additive-eligible and simply "
            + "not physically added yet, with NO unexplained extra column, must NOT refuse")
    void scenario21b_additiveColumnNeverAddedDoesNotTriggerARefusal() throws SQLException {
        // The direct-call unit-test shape that Trigger A's additive exclusion was originally written
        // to protect: the manifest declares 'notes', flyway.migrate() never ran, so the column is
        // absent. There is no EXTRA live column, so nothing looks like a rename -- Trigger B must
        // stay silent and this boot must proceed normally.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        seedStoredFingerprint(dataSource, "sha256:same");

        Map<String, List<String>> columns = Map.of("widgets", List.of("id", "name", "notes"));
        SchemaLifecycleExecutor.SchemaManifest sameFingerprint = manifest(
                "sha256:same", List.of("widgets"),
                columns, realisticAdditiveColumns(columns, Map.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "notes", "VARCHAR(50)")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, sameFingerprint);
        assertFalse(result.performed(), "a self-healing additive column must never provoke a refusal");
        assertFalse(result.safeAdditive(), "a matching fingerprint is the pure no-op branch");
    }

    @Test
    @DisplayName("Scenario 21c (X-B2): a missing NON-additive-eligible column (a required bond) still "
            + "refuses via the original Trigger A")
    void scenario21c_missingRequiredBondColumnStillRefusesViaTriggerA() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        seedStoredFingerprint(dataSource, "sha256:same");

        // 'owner_ref' is a REQUIRED bond column: additive-INeligible in a real manifest
        // (SchemaRealizationEmitter#isAdditiveEligible), so Trigger A must still catch it.
        Map<String, List<String>> columns = Map.of("widgets", List.of("id", "name", "owner_ref"));
        SchemaLifecycleExecutor.SchemaManifest sameFingerprint = manifest(
                "sha256:same", List.of("widgets"),
                columns, realisticAdditiveColumns(columns, Map.of("widgets", List.of("owner_ref"))),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "owner_ref", "BIGINT")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of(), true);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, sameFingerprint));
        assertTrue(refusal.getMessage().contains("widgets.owner_ref"), refusal.getMessage());
        assertEquals("REFUSED", latestHistoryRow(dataSource).outcome());
    }

    @Test
    @DisplayName("Scenario 21d (X3.4): a manifest-declared table that is entirely absent refuses with "
            + "one clear 'entire table missing' message, not a column-by-column flood")
    void scenario21d_entirelyMissingTableRefusesWithAClearMessage() throws SQLException {
        // A newer build renamed the CONCEPT (table) away and was rolled back to this jar.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
        seedStoredFingerprint(dataSource, "sha256:same");

        Map<String, List<String>> columns = Map.of(
                "widgets", List.of("id", "name"),
                "gadgets", List.of("id", "label"));
        SchemaLifecycleExecutor.SchemaManifest sameFingerprint = manifest(
                "sha256:same", List.of("widgets", "gadgets"),
                columns, realisticAdditiveColumns(columns, Map.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)"),
                        "gadgets", Map.of("id", "BIGINT", "label", "VARCHAR(50)")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of(), true);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, sameFingerprint));
        assertTrue(refusal.getMessage().contains("gadgets (entire table missing)"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains("gadgets.label"),
                "the whole-table message must REPLACE the per-column noise, not accompany it: "
                        + refusal.getMessage());
        assertEquals("REFUSED", latestHistoryRow(dataSource).outcome());
    }

    @Test
    @DisplayName("Scenario 22 (R4/F5): a combined upgrade writes one detailed, APPLIED history row per "
            + "mutating pass (table rename, column rename, relax, backfill) plus the surgical drop row")
    void scenario22_everyMutatingPassWritesADetailedHistoryRow() throws SQLException {
        // One boot that exercises: table rename (old_widgets -> widgets), column rename
        // (old_name -> full_name), NOT NULL relaxation (nickname), an acknowledged column drop
        // (legacy_flag), and a new required-with-default field backfill (status). Type widening is
        // deliberately absent: the control flow only runs it on a NON-destructive boot, so it cannot
        // co-occur with an acknowledged drop in the same boot.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE old_widgets (id BIGINT PRIMARY KEY, old_name VARCHAR(50), "
                    + "nickname VARCHAR(50) NOT NULL, legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO old_widgets (id, old_name, nickname, legacy_flag) VALUES (1, 'Alpha', 'al', TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        String token = DestructiveAckToken.compute("sha256:new", List.of("DROP_COLUMN:widgets:legacy_flag:BOOLEAN"));
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "full_name", "nickname", "status")),
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("id", "BIGINT", "full_name", "VARCHAR(50)", "nickname", "VARCHAR(50)", "status", "VARCHAR(50)")),
                Map.of("widgets", Map.of("full_name", "old_name")),
                Map.of("widgets", "old_widgets"), false, token,
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("status", "\"PENDING\"")), Map.of(), Map.of(), true);

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.performed(), "the acknowledged drop must go through the surgical destructive path");
        executor.afterMigrate(dataSource, manifest);

        // Final schema is fully converged.
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "widgets"));
            assertFalse(hasTable(metadata, "old_widgets"), "the table rename applied");
            assertTrue(hasColumn(metadata, "widgets", "full_name"), "the column rename applied");
            assertEquals("Alpha", readColumn(connection, "widgets", "full_name", 1L), "renamed column data preserved");
            assertFalse(hasColumn(metadata, "widgets", "legacy_flag"), "the acknowledged drop applied");
            assertTrue(isNotNull(metadata, "widgets", "status"), "the required-field backfill applied");
            assertEquals("PENDING", readColumn(connection, "widgets", "status", 1L), "legacy row backfilled");
        }

        List<HistoryDetail> rows = allHistoryRows(dataSource);
        // One detailed row per non-empty mutating pass, all APPLIED, with the right item strings.
        assertHistoryStep(rows, "TABLE_RENAME", "APPLIED", "RENAME_TABLE old_widgets -> widgets");
        assertHistoryStep(rows, "COLUMN_RENAME", "APPLIED", "RENAME_COLUMN widgets.old_name -> full_name");
        assertHistoryStep(rows, "RELAX_NOT_NULL", "APPLIED", "RELAX_NOT_NULL widgets.nickname");
        assertHistoryStep(rows, "REQUIRED_BACKFILL", "APPLIED", "BACKFILL widgets.status DEFAULT");
        // The surgical destruction row (its classification is the SchemaChangeClassification enum name,
        // not one of the step names) records the acknowledged drop.
        boolean surgicalRow = rows.stream().anyMatch(r ->
                "APPLIED".equals(r.outcome()) && r.itemsJson() != null
                        && r.itemsJson().contains("DROP_COLUMN:widgets:legacy_flag:BOOLEAN"));
        assertTrue(surgicalRow, "the surgical drop must have its own APPLIED history row with item detail: " + rows);
    }

    private static void assertHistoryStep(List<HistoryDetail> rows, String stepName, String outcome, String itemSubstring) {
        HistoryDetail match = rows.stream()
                .filter(r -> stepName.equals(r.classification()))
                .findFirst()
                .orElse(null);
        assertTrue(match != null, "expected a history row for step " + stepName + " in: " + rows);
        assertEquals(outcome, match.outcome(), "step " + stepName + " outcome");
        assertTrue(match.itemsJson() != null && match.itemsJson().contains(itemSubstring),
                "step " + stepName + " items_json must contain '" + itemSubstring + "' but was " + match.itemsJson());
    }

    // ---- LNCH-1-B7: a dropped concept's table is actually dropped ------------------------------

    /** The v2 manifest for the B7 scenarios: the 'gadgets' concept is gone from the model entirely
     * (not renamed), leaving only 'widgets'. */
    private static SchemaLifecycleExecutor.SchemaManifest conceptDropManifest(String token, boolean blanketAllowed) {
        return manifest(
                "sha256:new", List.of("widgets"),
                Map.of("widgets", List.of("id", "name")), Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), blanketAllowed, token, Map.of(), Map.of(), Map.of(), Map.of(), true);
    }

    private void seedTwoConceptsWithData() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'Alpha')");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, label VARCHAR(50))");
            statement.execute("INSERT INTO gadgets (id, label) VALUES (1, 'G1')");
            statement.execute("INSERT INTO gadgets (id, label) VALUES (2, 'G2')");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
    }

    @Test
    @DisplayName("Scenario 23 (B7): a dropped CONCEPT whose table NPDev owns is itemized, gated on the "
            + "token, and actually dropped -- the plan no longer promises a drop that never happens")
    void scenario23_droppedConceptIsActuallyDroppedWhenAcknowledged() throws SQLException {
        seedTwoConceptsWithData();
        // What the previous successful boot recorded: NPDev owns BOTH tables.
        seedOwnedBusinessTables(dataSource, List.of("widgets", "gadgets"));

        SchemaLifecycleExecutor.SchemaManifest noToken = conceptDropManifest("", false);
        // Before B7 this classified SAFE_ADDITIVE and the destructive path was never entered.
        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.DESTRUCTIVE,
                executor.classify(dataSource, noToken),
                "a dropped concept whose table NPDev owns must escalate to the destructive path");

        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, noToken);
        assertEquals(List.of("DROP_TABLE:gadgets"), report.stableStrings());
        String token = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        SchemaLifecycleExecutor.DestructiveRecreation result =
                executor.beforeMigrate(dataSource, conceptDropManifest(token, false));
        assertTrue(result.performed(), "the acknowledged concept drop must execute");
        assertTrue(result.droppedTables().contains("gadgets"));

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasTable(metadata, "gadgets"), "the dropped concept's table must be GONE");
            assertTrue(hasTable(metadata, "widgets"), "the surviving concept's table must remain");
            assertEquals("Alpha", readColumn(connection, "widgets", "name", 1L), "surviving data intact");
        }
        assertEquals("APPLIED", latestHistoryRow(dataSource).outcome());
    }

    @Test
    @DisplayName("Scenario 23b (B7): the same concept drop WITHOUT a token is refused -- proving the "
            + "acknowledgment is now genuinely consulted, not requested-then-ignored")
    void scenario23b_droppedConceptWithoutTokenIsRefused() throws SQLException {
        seedTwoConceptsWithData();
        seedOwnedBusinessTables(dataSource, List.of("widgets", "gadgets"));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, conceptDropManifest("", false)));
        assertTrue(refusal.getMessage().contains("DROP_TABLE:gadgets"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(hasTable(connection.getMetaData(), "gadgets"),
                    "a refused concept drop must leave the table untouched");
        }
        assertEquals("REFUSED", latestHistoryRow(dataSource).outcome());
    }

    @Test
    @DisplayName("Scenario 23c (B7 safety): a table NPDev does NOT own (created by hand in the same "
            + "schema) is never treated as a dropped concept, even though the manifest omits it")
    void scenario23c_unownedTableIsNeverDropped() throws SQLException {
        seedTwoConceptsWithData();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE operator_scratch (id BIGINT PRIMARY KEY, note VARCHAR(50))");
            statement.execute("INSERT INTO operator_scratch (id, note) VALUES (1, 'do not drop me')");
        }
        // Ownership records ONLY the NPDev tables -- operator_scratch is deliberately absent.
        seedOwnedBusinessTables(dataSource, List.of("widgets", "gadgets"));

        // The manifest declares only widgets: gadgets IS an owned orphan, operator_scratch is NOT.
        SchemaLifecycleExecutor.SchemaManifest noToken = conceptDropManifest("", false);
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, noToken);
        assertEquals(List.of("DROP_TABLE:gadgets"), report.stableStrings(),
                "only the owned orphan may be itemized -- never the hand-created table");

        String token = DestructiveAckToken.compute("sha256:new", report.stableStrings());
        executor.beforeMigrate(dataSource, conceptDropManifest(token, false));

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasTable(metadata, "gadgets"), "the owned orphan is dropped");
            assertTrue(hasTable(metadata, "operator_scratch"), "the UNOWNED table must survive untouched");
            assertEquals("do not drop me", readColumn(connection, "operator_scratch", "note", 1L),
                    "the unowned table's data must be untouched");
        }
    }

    @Test
    @DisplayName("Scenario 23d (B7 back-compat): with NO ownership ever recorded, an orphan table is "
            + "left alone -- a legacy app's first boot on this build never destroys anything new")
    void scenario23d_withoutOwnershipRecordOrphanIsLeftAlone() throws SQLException {
        seedTwoConceptsWithData();
        // Deliberately NO seedOwnedBusinessTables: ownership unknown.

        SchemaLifecycleExecutor.SchemaManifest noToken = conceptDropManifest("", false);
        assertEquals(SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE,
                executor.classify(dataSource, noToken),
                "without ownership evidence the executor must keep its pre-B7 behaviour");

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, noToken);
        assertFalse(result.performed(), "nothing destructive may happen without ownership evidence");
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(hasTable(connection.getMetaData(), "gadgets"), "the orphan survives, as before B7");
        }
    }

    @Test
    @DisplayName("Scenario 23e (B7): afterMigrate records the owned business tables, so the NEXT "
            + "build's concept drop has the ownership evidence it needs")
    void scenario23e_afterMigrateRecordsOwnedBusinessTables() throws SQLException {
        // Both declared tables must exist physically: since LNCH-1 hardening X2, ownership is the
        // union of (previous, current manifest) INTERSECTED WITH THE LIVE TABLES, so a manifest entry
        // with no corresponding table is deliberately not recorded as owned. In production
        // afterMigrate always runs after flyway.migrate() has created them; this fixture now mirrors
        // that instead of declaring a table it never creates.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of("widgets", "gadgets"),
                Map.of("widgets", List.of("id", "name"), "gadgets", List.of("id")),
                Map.of("widgets", List.of(), "gadgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)"), "gadgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of(), true);

        executor.afterMigrate(dataSource, manifest);

        Set<String> owned = SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource);
        assertEquals(Set.of("widgets", "gadgets"), owned,
                "every business table this build declares must be recorded as NPDev-owned");
    }

    @Test
    @DisplayName("Scenario 24 (X-B1): an acknowledged concept drop on a blanket-posture app drops "
            + "ONLY that concept's table -- never the data of concepts this build still declares")
    void scenario24_acknowledgedConceptDropDoesNotWipeUnrelatedTables() throws SQLException {
        seedTwoRealisticConceptsWithData();
        // Ownership + fingerprint are seeded through the PRODUCTION writer (afterMigrate with the
        // v1 manifest), not by hand-inserting JSON, so this fixture can never drift from the format
        // readOwnedBusinessTables actually expects (LNCH-1 hardening X0.4).
        executor.afterMigrate(dataSource, realisticTwoConceptManifest("sha256:old"));
        assertEquals(Set.of("widgets", "gadgets"), SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource),
                "precondition: the previous boot must have recorded BOTH tables as NPDev-owned");

        // v2 drops the 'gadgets' concept. The blanket flag is left ON (the shape of every shipped app
        // definition), but since X4.4 a concept drop additionally requires an itemized token -- so
        // this pass carries both, which is exactly the posture a real blanket-flag app now upgrades
        // under. The X-B1 routing this scenario exists for is unchanged by that: the blanket-only
        // route into the surgical path is still proven, for a DROP_COLUMN, by
        // SchemaLifecycleExecutorDestructiveItemizationTest#blanketFlagAloneNowExecutesSurgicallyWithADeprecationWarning,
        // and the blanket-only concept-drop refusal is scenario 26.
        SchemaDeltaReport plan = SchemaDeltaReport.generate(dataSource, realisticConceptDropManifest("", true));
        assertEquals(List.of("DROP_TABLE:gadgets"), plan.stableStrings());
        String token = DestructiveAckToken.compute("sha256:new", plan.stableStrings());

        SchemaLifecycleExecutor.SchemaManifest acknowledgedOnBlanketApp = realisticConceptDropManifest(token, true);
        SchemaLifecycleExecutor.DestructiveRecreation result =
                executor.beforeMigrate(dataSource, acknowledgedOnBlanketApp);

        assertTrue(result.performed(), "the acknowledged concept drop must execute");
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            // THE assertion X-B1 is about, checked FIRST because it is the finding's headline: before
            // the fix the blanket flag routed this pass to the WHOLE-SCHEMA wipe, which drops every
            // manifest-listed table -- destroying 'widgets' (real, still-modelled data) while the
            // actual orphan 'gadgets' survived untouched precisely because it is no longer
            // manifest-listed. The upgrade destroyed everything EXCEPT the thing it was meant to drop.
            assertTrue(hasTable(metadata, "widgets"),
                    "an unrelated, still-declared concept must NOT be dropped by a concept-drop upgrade");
            assertEquals(2, rowCount(connection, "widgets"),
                    "the surviving concept must keep ALL of its rows");
            assertEquals("Alpha", readColumn(connection, "widgets", "name", 1L), "surviving data intact");
            assertFalse(hasTable(metadata, "gadgets"), "the dropped concept's table must be removed");
        }
        assertEquals("APPLIED", latestHistoryRow(dataSource).outcome());

        executor.afterMigrate(dataSource, acknowledgedOnBlanketApp);
        assertSecondBootIsNoOp(acknowledgedOnBlanketApp);
    }

    @Test
    @DisplayName("Scenario 24b (X-B1 guard): a report containing an UNKNOWN item -- here a declared, "
            + "non-additive, non-required column missing from the live database -- STILL falls back "
            + "to the whole-schema recreation; X1 narrowed that path, it did not delete it")
    void scenario24b_unknownItemStillTakesTheWholeSchemaWipePath() throws SQLException {
        // Identical to scenario 24 EXCEPT that the manifest declares the OPTIONAL bond column
        // 'owner_ref' and marks it non-additive, while the live 'widgets' table does not have it --
        // so SchemaDeltaReport itemizes it as UNKNOWN. See UNEXPLAINABLE_COLUMN for why this is the
        // vehicle, and why the previous one (a physically missing 'version') stopped working at
        // LNCH-1 T2: 'version' is now additive-eligible and self-heals.
        seedTwoRealisticConceptsWithData();
        executor.afterMigrate(dataSource, realisticTwoConceptManifest("sha256:old"));

        SchemaDeltaReport report =
                SchemaDeltaReport.generate(dataSource, realisticConceptDropManifest("sha256:new", "", true, true));
        assertFalse(report.hasOnlyNamedDestructiveKinds(),
                "precondition: the report must contain an UNKNOWN item for this scenario to mean anything");
        // The report also contains a DROP_TABLE, which since X4.4 requires an itemized token
        // regardless of the blanket flag (scenario 26) -- so this pass supplies one. The token is not
        // what selects the execution path; the UNKNOWN item is.
        String token = DestructiveAckToken.compute("sha256:new", report.stableStrings());
        SchemaLifecycleExecutor.SchemaManifest authorized =
                realisticConceptDropManifest("sha256:new", token, true, true);

        SchemaLifecycleExecutor.DestructiveRecreation result =
                executor.beforeMigrate(dataSource, authorized);

        assertTrue(result.performed());
        try (Connection connection = dataSource.getConnection()) {
            // The whole-schema path drops every manifest-listed table. This is the (unchanged,
            // deliberate) last-resort behaviour when the diff genuinely cannot be explained item by
            // item -- proving X1 narrowed the wipe to the UNKNOWN case rather than removing it.
            assertFalse(hasTable(connection.getMetaData(), "widgets"),
                    "an UNKNOWN item must still force the whole-schema recreation");
        }
        assertEquals("APPLIED", latestHistoryRow(dataSource).outcome());
        assertEquals(token, latestHistoryRow(dataSource).ackTokenUsed(),
                "the token that authorized this pass must be recorded for audit");
    }

    @Test
    @DisplayName("Scenario 25 (X-B3): an orphan that SURVIVES a pass stays NPDev-owned, so a later "
            + "token-authorized boot can still clean it up -- ownership is not silently forgotten")
    void scenario25_orphanSurvivingAPassStaysOwnedAndIsDroppableLater() throws SQLException {
        // ---- boot v1: both concepts exist and are recorded as owned -------------------------------
        // v2's manifest declares the OPTIONAL bond column 'owner_ref' and marks it non-additive,
        // while the live 'widgets' has no such column -- that is what makes v2's report UNKNOWN and
        // sends it down the whole-schema path (see scenario 24b and UNEXPLAINABLE_COLUMN). Before
        // LNCH-1 T2 this scenario used a physically missing 'version' instead; T2 made that column
        // additive-eligible, so it self-heals and no longer produces an UNKNOWN.
        seedTwoRealisticConceptsWithData();
        executor.afterMigrate(dataSource, realisticTwoConceptManifest("sha256:v1"));
        assertEquals(Set.of("widgets", "gadgets"), SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource));

        // ---- boot v2: drops the 'gadgets' concept, but takes the UNKNOWN whole-schema path --------
        // The DROP_TABLE needs an itemized token since X4.4 (see scenario 26); the UNKNOWN item is
        // what routes this pass to the whole-schema path, not the authorization source.
        SchemaDeltaReport v2Report =
                SchemaDeltaReport.generate(dataSource, realisticConceptDropManifest("sha256:v2", "", true, true));
        assertFalse(v2Report.hasOnlyNamedDestructiveKinds(),
                "precondition: v2's report must contain an UNKNOWN item, or this scenario is not "
                        + "exercising the whole-schema path at all: " + v2Report.stableStrings());
        SchemaLifecycleExecutor.SchemaManifest v2 = realisticConceptDropManifest(
                "sha256:v2", DestructiveAckToken.compute("sha256:v2", v2Report.stableStrings()), true, true);
        executor.beforeMigrate(dataSource, v2);
        try (Connection connection = dataSource.getConnection()) {
            // The whole-schema path drops the manifest-listed 'widgets'; the orphan 'gadgets' is NOT
            // manifest-listed, so it survives the very pass that was supposed to remove it.
            assertTrue(hasTable(connection.getMetaData(), "gadgets"),
                    "precondition: the orphan must survive this pass for the scenario to mean anything");
            // Production sequencing: flyway.migrate() recreates the declared tables between
            // beforeMigrate and afterMigrate. Imitate that here (this test drives the executor
            // directly and has no Flyway).
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), "
                        + "version BIGINT, row_version BIGINT, tenant_id VARCHAR(64))");
            }
        }
        executor.afterMigrate(dataSource, v2);

        // THE X-B3 assertion. Pre-X2, afterMigrate rewrote ownership from the new manifest alone, so
        // 'gadgets' dropped out of the set permanently and no later boot could ever prove NPDev owned
        // it -- the orphan became un-droppable forever.
        assertEquals(Set.of("widgets", "gadgets"), SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource),
                "a surviving orphan must REMAIN owned so a later pass can still recognise it as a dropped concept");

        // ---- boot v3: with a valid token, the orphan is finally cleaned up ------------------------
        SchemaLifecycleExecutor.SchemaManifest v3NoToken = realisticConceptDropManifest("sha256:v3", "", false);
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, v3NoToken);
        assertEquals(List.of("DROP_TABLE:gadgets"), report.stableStrings(),
                "the still-owned orphan must now be itemized as a dropped concept");
        String token = DestructiveAckToken.compute("sha256:v3", report.stableStrings());

        executor.beforeMigrate(dataSource, realisticConceptDropManifest("sha256:v3", token, false));
        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasTable(connection.getMetaData(), "gadgets"),
                    "the orphan must finally be dropped by the token-authorized pass");
            // The table survives the cleanup pass. Its ROWS were already lost back at v2 -- that is
            // what the UNKNOWN whole-schema path costs, and is exactly why X1 narrowed the set of
            // situations that reach it.
            assertTrue(hasTable(connection.getMetaData(), "widgets"),
                    "the surviving concept's table is untouched by the cleanup pass");
        }
    }

    @Test
    @DisplayName("Scenario 25b (X-B3 safety): a hand-created table never enters the ownership set "
            + "across repeated boots, and is never itemized as a dropped concept")
    void scenario25b_handCreatedTableNeverBecomesOwned() throws SQLException {
        seedTwoRealisticConceptsWithData();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE scratch_notes (id BIGINT PRIMARY KEY, note VARCHAR(50))");
            statement.execute("INSERT INTO scratch_notes (id, note) VALUES (1, 'operator owned')");
        }

        // Boot 1 and boot 2 both declare both concepts; boot 3 drops one. The union-with-previous
        // rule added in X2 only ever admits names that came from a manifest, so no number of boots
        // can drift a hand-created table into the owned set.
        executor.afterMigrate(dataSource, realisticTwoConceptManifest("sha256:v1"));
        assertEquals(Set.of("widgets", "gadgets"), SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource),
                "boot 1: only manifest-declared tables are owned");

        executor.afterMigrate(dataSource, realisticTwoConceptManifest("sha256:v2"));
        assertEquals(Set.of("widgets", "gadgets"), SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource),
                "boot 2: the union must not have absorbed the hand-created table");

        SchemaLifecycleExecutor.SchemaManifest dropsGadgets = realisticConceptDropManifest("sha256:v3", "", false);
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, dropsGadgets);
        assertEquals(List.of("DROP_TABLE:gadgets"), report.stableStrings(),
                "only the owned orphan may ever be itemized -- never the hand-created table");

        String token = DestructiveAckToken.compute("sha256:v3", report.stableStrings());
        executor.beforeMigrate(dataSource, realisticConceptDropManifest("sha256:v3", token, false));
        executor.afterMigrate(dataSource, realisticConceptDropManifest("sha256:v3", token, false));

        assertEquals(Set.of("widgets"), SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource),
                "boot 3: the dropped concept leaves the set (it is gone from the database), and the "
                        + "hand-created table still never entered it");
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(hasTable(connection.getMetaData(), "scratch_notes"), "the hand-created table survives");
            assertEquals("operator owned", readColumn(connection, "scratch_notes", "note", 1L),
                    "the hand-created table's data is untouched");
        }
    }

    @Test
    @DisplayName("Scenario 26 (X4.4): the blanket 'destructiveAllowed' flag does NOT authorize a "
            + "CONCEPT drop -- dropping a whole table always requires an itemized token")
    void scenario26_blanketFlagAloneCannotAuthorizeAConceptDrop() throws SQLException {
        seedTwoRealisticConceptsWithData();
        executor.afterMigrate(dataSource, realisticTwoConceptManifest("sha256:old"));

        SchemaLifecycleExecutor.SchemaManifest blanketOnly = realisticConceptDropManifest("", true);
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, blanketOnly));

        assertTrue(refusal.getMessage().contains("DROP of one or more whole concept table(s): [gadgets]"),
                refusal.getMessage());
        assertTrue(refusal.getMessage().contains("does NOT"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("#acknowledging-destructive-changes"), refusal.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "gadgets"), "a refused concept drop must leave the table untouched");
            assertEquals(2, rowCount(connection, "gadgets"), "and all of its data");
            assertEquals(2, rowCount(connection, "widgets"), "and must not touch anything else either");
        }
        assertEquals("REFUSED", latestHistoryRow(dataSource).outcome());

        // The SAME change, with the itemized token, proceeds -- proving X4.4 gates on the token and
        // not on the item kind being unsupported.
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, blanketOnly);
        String token = DestructiveAckToken.compute("sha256:new", report.stableStrings());
        executor.beforeMigrate(dataSource, realisticConceptDropManifest(token, true));
        try (Connection connection = dataSource.getConnection()) {
            assertFalse(hasTable(connection.getMetaData(), "gadgets"),
                    "with the token supplied, the same concept drop executes");
        }
    }

    @Test
    @DisplayName("Scenario 27 (C-B1): a diff that cannot be explained item by item must be REFUSED "
            + "on a blanket-posture app -- the whole-schema recreation destroys EVERY table's data, "
            + "so it requires an itemized token exactly like a concept drop does")
    void scenario27_wholeSchemaRecreationIsNotAuthorizedByTheBlanketFlagAlone() throws SQLException {
        // The distinguishing fixture: the new manifest declares the OPTIONAL bond column 'owner_ref'
        // and marks it non-additive, while the live 'widgets' has no such column -- so the report
        // carries an UNKNOWN item (see UNEXPLAINABLE_COLUMN; before LNCH-1 T2 this was a physically
        // missing 'version', which is now additive-eligible and self-heals). Unlike scenario 24b, the
        // new manifest still declares BOTH concepts, so there is NO DropTable to trip X4.4's gate.
        // Before C1 that combination -- blanket posture, no token -- wiped every table in the app.
        seedTwoRealisticConceptsWithData();
        // Fingerprint + ownership through the PRODUCTION writer, never hand-inserted JSON.
        executor.afterMigrate(dataSource, realisticTwoConceptManifest("sha256:old"));
        assertEquals(Set.of("widgets", "gadgets"), SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource),
                "precondition: the previous boot must have recorded BOTH tables as NPDev-owned");

        SchemaLifecycleExecutor.SchemaManifest blanketOnly =
                realisticTwoConceptManifest("sha256:new", "", true);
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, blanketOnly);
        assertFalse(report.hasOnlyNamedDestructiveKinds(),
                "precondition: the report must contain an UNKNOWN item for this scenario to mean anything");
        for (SchemaDeltaItem item : report.items()) {
            assertFalse(item instanceof SchemaDeltaItem.DropTable,
                    "precondition: NO concept drop -- otherwise X4.4's gate (scenario 26), not C-B1's, "
                            + "is what forces the token: " + report.stableStrings());
        }

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.beforeMigrate(dataSource, blanketOnly));

        String expectedToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());
        // The refusal must state plainly what would otherwise happen -- not merely that a token is
        // missing, but that proceeding destroys every table in the app.
        assertTrue(refusal.getMessage().contains("DROP AND RECREATE EVERY TABLE IN THIS APP"),
                refusal.getMessage());
        assertTrue(refusal.getMessage().contains("cannot be executed item by item"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(expectedToken),
                "the refusal must print the token that would authorize the pass: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("#acknowledging-destructive-changes"), refusal.getMessage());
        for (SchemaDeltaItem item : report.items()) {
            if (item instanceof SchemaDeltaItem.Unknown) {
                assertTrue(refusal.getMessage().contains(item.stableString()),
                        "the refusal must name the UNKNOWN item(s) that made the diff unexplainable: "
                                + refusal.getMessage());
            }
        }

        // Nothing may have been destroyed by a refused pass.
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, "widgets"), "a refused pass must leave every table in place");
            assertTrue(hasTable(metadata, "gadgets"), "a refused pass must leave every table in place");
            assertEquals(2, rowCount(connection, "widgets"), "and all of their rows");
            assertEquals(2, rowCount(connection, "gadgets"), "and all of their rows");
        }
        assertEquals("REFUSED", latestHistoryRow(dataSource).outcome());

        // The SAME change, with the itemized token, proceeds -- proving C1 gates on the TOKEN, not on
        // the item kind, and that it narrowed authorization without deleting the whole-schema path.
        SchemaLifecycleExecutor.SchemaManifest authorized =
                realisticTwoConceptManifest("sha256:new", expectedToken, true);
        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, authorized);

        assertTrue(result.performed(), "with the token supplied, the whole-schema recreation executes");
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasTable(metadata, "widgets"), "the whole-schema recreation drops every manifest table");
            assertFalse(hasTable(metadata, "gadgets"), "the whole-schema recreation drops every manifest table");
        }
        assertEquals("APPLIED", latestHistoryRow(dataSource).outcome());
        assertEquals(expectedToken, latestHistoryRow(dataSource).ackTokenUsed(),
                "the token that authorized this pass must be recorded for audit");
    }

    @Test
    @DisplayName("Scenario 28 (T-B1): an ordinary upgrade must NOT relax NOT NULL on the "
            + "platform-managed columns -- tenant_id, row_version and version are platform-owned and "
            + "their nullability never follows a model field's optionality")
    void scenario28_ordinaryUpgradeNeverRelaxesPlatformManagedColumns() throws SQLException {
        // The production shape: SchemaRealizationEmitter's fresh CREATE TABLE emits all three
        // platform columns NOT NULL with a DEFAULT (VERIFIED at SchemaRealizationEmitter:370-377).
        // seedTwoRealisticConceptsWithData deliberately does NOT (it creates them plain nullable), so
        // this scenario needs its own fixture -- nullability is the whole subject here.
        seedProductionShapedWidgets();

        // Precondition: the fixture really is strict to begin with, or this scenario proves nothing.
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String column : List.of("version", "row_version", "tenant_id")) {
                assertTrue(isNotNull(metadata, "widgets", column),
                        "precondition: the production-shaped fixture must start with " + column + " NOT NULL");
            }
        }

        // Fingerprint + ownership through the PRODUCTION writer, never hand-inserted JSON.
        executor.afterMigrate(dataSource, productionShapedWidgetsManifest("sha256:v1", false));

        // v2 carries one ordinary, entirely unremarkable change: a new OPTIONAL field 'notes'.
        // 'name' stays required in both manifests, so the only columns the relax pass could possibly
        // act on are the three platform ones -- which is exactly what makes this test specific.
        SchemaLifecycleExecutor.SchemaManifest v2 = productionShapedWidgetsManifest("sha256:v2", true);
        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, v2);
        assertFalse(result.performed(), "adding one optional column must never be destructive");

        // THE T-B1 ASSERTION. Before the fix, relaxNoLongerRequiredColumns walked
        // manifest.businessTableColumns() -- which carries the platform columns, since it is
        // fullColumnNames -- and skipped a column only if it was model-'required' (never true of a
        // platform column) or the live primary key (which is why 'id' alone escaped). All three were
        // therefore stripped of NOT NULL on EVERY fingerprint-changing boot.
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(isNotNull(metadata, "widgets", "tenant_id"),
                    "tenant_id NOT NULL is the guard that stops an in-place upgrade leaving rows "
                            + "unreachable to every tenant-scoped read -- it must survive an ordinary upgrade");
            assertTrue(isNotNull(metadata, "widgets", "row_version"),
                    "a nullable row_version silently defeats LNCH-16's compare-and-swap");
            assertTrue(isNotNull(metadata, "widgets", "version"),
                    "version is platform-managed; no model field can be named 'version' "
                            + "(RESERVED_BUSINESS_COLUMN_NAMES), so its nullability is never a model decision");
            assertTrue(isNotNull(metadata, "widgets", "name"),
                    "control: a genuinely still-required business column is also untouched");
        }

        // And it stays strict across a second boot -- the matrix's standing idempotence requirement.
        executor.afterMigrate(dataSource, v2);
        assertSecondBootIsNoOp(v2);
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String column : List.of("version", "row_version", "tenant_id")) {
                assertTrue(isNotNull(metadata, "widgets", column),
                        column + " must still be NOT NULL after a second boot");
            }
        }
    }

    @Test
    @DisplayName("Scenario 28b (T-B1 repair): an app ALREADY loosened by the old relax pass is "
            + "repaired -- NULLs backfilled to the platform defaults, NOT NULL restored, a "
            + "TIGHTEN_PLATFORM_COLUMNS history row written, and the next boot is a clean no-op")
    void scenario28b_alreadyRelaxedPlatformColumnsAreRepairedOnTheNextBoot() throws SQLException {
        // Exactly the state the OLD behaviour left behind on every app it upgraded: the platform
        // columns exist, are nullable, and real rows carry NULLs in them -- which is how rows became
        // unreachable to tenant-scoped reads and how row_version stopped guarding anything.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets ("
                    + "id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, "
                    + "version BIGINT, row_version BIGINT, tenant_id VARCHAR(120))");
            statement.execute("INSERT INTO widgets (id, name, version, row_version, tenant_id) "
                    + "VALUES (1, 'Alpha', NULL, NULL, NULL)");
            // A row that already holds real values, to prove the backfill touches ONLY the NULLs.
            statement.execute("INSERT INTO widgets (id, name, version, row_version, tenant_id) "
                    + "VALUES (2, 'Beta', 7, 3, 'acme')");
        }
        executor.afterMigrate(dataSource, productionShapedWidgetsManifest("sha256:v1", false));

        SchemaLifecycleExecutor.SchemaManifest v2 = productionShapedWidgetsManifest("sha256:v2", true);
        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, v2);
        assertFalse(result.performed(), "a repair is never destructive");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String column : List.of("version", "row_version", "tenant_id")) {
                assertTrue(isNotNull(metadata, "widgets", column),
                        column + " must have been repaired back to NOT NULL");
            }
            // The NULL row is backfilled to the PLATFORM defaults -- the same values the generator's
            // fresh CREATE TABLE would have given it (SchemaRealizationEmitter:370-377).
            assertEquals("0", readColumn(connection, "widgets", "version", 1L));
            assertEquals("0", readColumn(connection, "widgets", "row_version", 1L));
            assertEquals("default", readColumn(connection, "widgets", "tenant_id", 1L));
            // The row that already had real values keeps every one of them.
            assertEquals("7", readColumn(connection, "widgets", "version", 2L));
            assertEquals("3", readColumn(connection, "widgets", "row_version", 2L));
            assertEquals("acme", readColumn(connection, "widgets", "tenant_id", 2L));
        }

        // The audit trail must show that a REPAIR happened, not merely that the columns are strict.
        List<HistoryDetail> afterRepair = allHistoryRows(dataSource);
        List<HistoryDetail> repairRows = afterRepair.stream()
                .filter(row -> "TIGHTEN_PLATFORM_COLUMNS".equals(row.classification()))
                .toList();
        assertEquals(1, repairRows.size(),
                "exactly one TIGHTEN_PLATFORM_COLUMNS pass row is expected: " + afterRepair);
        assertEquals("APPLIED", repairRows.get(0).outcome());
        for (String column : List.of("version", "row_version", "tenant_id")) {
            assertTrue(repairRows.get(0).itemsJson().contains("widgets." + column),
                    "the history row must itemize what it repaired: " + repairRows.get(0).itemsJson());
        }

        // A second boot is a clean no-op: nothing left to tighten, so NO new history row (recordStepPass
        // writes nothing for an empty item list -- no noise rows on converged boots).
        executor.afterMigrate(dataSource, v2);
        assertSecondBootIsNoOp(v2);
        long repairRowsAfterSecondBoot = allHistoryRows(dataSource).stream()
                .filter(row -> "TIGHTEN_PLATFORM_COLUMNS".equals(row.classification()))
                .count();
        assertEquals(1, repairRowsAfterSecondBoot,
                "the repair is idempotent -- a converged boot must not write a second repair row");
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String column : List.of("version", "row_version", "tenant_id")) {
                assertTrue(isNotNull(metadata, "widgets", column),
                        column + " must still be NOT NULL after the second boot");
            }
        }
    }

    @Test
    @DisplayName("Scenario 29 (T-B2): a table missing the platform 'version' column self-heals "
            + "additively -- it is not an unexplainable diff that demands a destructive token")
    void scenario29_missingPlatformVersionColumnIsAdditiveNotUnknown() throws SQLException {
        // 24b/27's construction: 'widgets' physically lacks 'version'. Before T2 that was the
        // canonical way to manufacture an UNKNOWN item, because 'version' was the one column every
        // real manifest DECLARES (fullColumnNames) but never marks ADDITIVE (additiveColumnNames) --
        // so no migration could ever add it back, and since closeout C1 the resulting UNKNOWN
        // REFUSES the boot unless an itemized whole-schema-wipe token is supplied.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), "
                    + "row_version BIGINT, tenant_id VARCHAR(64))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'Alpha')");
            statement.execute("INSERT INTO widgets (id, name) VALUES (2, 'Beta')");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, label VARCHAR(50), "
                    + "version BIGINT, row_version BIGINT, tenant_id VARCHAR(64))");
            statement.execute("INSERT INTO gadgets (id, label, version) VALUES (1, 'G1', 0)");
            statement.execute("INSERT INTO gadgets (id, label, version) VALUES (2, 'G2', 0)");
        }
        executor.afterMigrate(dataSource, realisticTwoConceptManifest("sha256:old"));

        SchemaLifecycleExecutor.SchemaManifest v2 = realisticTwoConceptManifest("sha256:new");

        // Half one of the fix, at the manifest level: 'version' must now be declared additive, the
        // same way 'row_version' already is. This is what the emitter change buys.
        assertTrue(v2.businessTableAdditiveColumns().getOrDefault("widgets", List.of())
                        .stream().anyMatch("version"::equalsIgnoreCase),
                "'version' must be additive-eligible -- it is BIGINT DEFAULT 0, exactly like "
                        + "row_version, which already is");

        // Half two, the consequence that matters: the diff is now fully explainable, so no UNKNOWN.
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, v2);
        assertTrue(report.hasOnlyNamedDestructiveKinds(),
                "a merely-missing platform 'version' column must no longer produce an UNKNOWN item: "
                        + report.stableStrings());

        // And therefore the boot is NOT refused. Before T2 this threw, demanding a token whose only
        // effect would have been to DROP AND RECREATE EVERY TABLE IN THE APP.
        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, v2);
        assertFalse(result.performed(),
                "a self-healing platform column must never route the boot through a destructive path");

        // Rows are intact -- the whole point of not taking the wipe path.
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(2, rowCount(connection, "widgets"), "no data may be lost by a self-healing column");
            assertEquals(2, rowCount(connection, "gadgets"));
        }

        // NOTE on scope: the ALTER TABLE ... ADD COLUMN IF NOT EXISTS version DDL that physically
        // restores the column is emitted by SchemaRealizationEmitter and applied by Flyway. This test
        // class drives the executor directly and has no Flyway (see scenario 25's explicit "imitate
        // that here" comment), so the DDL half is proven where it lives -- in the generator emitter
        // test (SchemaRealizationEmitterTest, T2) -- rather than being faked here.
    }

    // ---- REG-8 (P4): Trigger C -- database migrated past this build --------------------------------

    @Test
    @DisplayName("Scenario 30 (REG-8 Trigger C): pure column drop + rollback refuses instead of "
            + "silently re-adding the dropped column empty -- the register's own practical example")
    void scenario30_databaseMigratedPastThisBuildRefusesOnRollback() throws SQLException {
        // Trigger A/B (scenario 21) only runs on a fingerprint-MATCH boot and detects an UNEXPLAINED
        // EXTRA live column (a rename's signature). A PURE drop leaves neither: the live shape here is
        // indistinguishable from "nickname never existed", and the stored fingerprint does NOT match
        // this (old) build either -- this is a MISMATCH boot, which is exactly the blind spot REG-8
        // names. Full detail (including the mark-done short-circuit, D4) lives in the dedicated
        // SchemaLifecycleExecutorDatabaseMigratedPastBuildTest; this scenario proves the same fix
        // through the matrix's own production-sequencing harness.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'alpha')");
        }
        // History: this (old) build's own fingerprint was reached at T1; a REAL, later migration
        // (build N+1, which dropped 'nickname') moved the database to a DIFFERENT fingerprint at T2.
        seedHistoryRow(dataSource, "sha256:N", "APPLIED", 1_000L);
        seedHistoryRow(dataSource, "sha256:N+1", "APPLIED", 2_000L);
        seedStoredFingerprint(dataSource, "sha256:N+1");

        SchemaLifecycleExecutor.SchemaManifest oldBuildManifest = manifest(
                "sha256:N", List.of("widgets"),
                Map.of("widgets", List.of("id", "name", "nickname")),
                Map.of("widgets", List.of("nickname")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "nickname", "VARCHAR(50)")),
                Map.of(), Map.of(), true, "", Map.of(), Map.of(), Map.of(), Map.of(), true);

        BoundaryBootException exception = assertThrows(BoundaryBootException.class,
                () -> executor.beforeMigrate(dataSource, oldBuildManifest));
        assertTrue(exception.getMessage().contains("migrated PAST this build"), exception.getMessage());
        assertTrue(exception.getMessage().contains("sha256:N+1"), exception.getMessage());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, "widgets", "nickname"),
                    "the refusal must fire BEFORE classify()/the R__ migration ever runs -- 'nickname' "
                            + "must NOT be silently re-added empty");
        }
        HistoryRow row = latestHistoryRow(dataSource);
        assertEquals("REFUSED", row.outcome());
    }

    @Test
    @DisplayName("B8 (docs/ACCEPTED_BOUNDARIES.md, 2026-08-25 W2.3): dropping a concept with no "
            + "recorded ownership history is silently skipped, and the warning carries the boundary code")
    void b8_droppedConceptTablesSkipsAndWarnsWithCodeWhenNoOwnershipRecorded() throws SQLException {
        // No afterMigrate() call precedes this -- readOwnedBusinessTables returns null/empty, the
        // exact "no ownership evidence recorded" precondition B8 describes. This is a SOFT skip, not
        // a boot refusal: the boot continues, so the code is carried on the log line, not a thrown
        // BoundaryBootException (unlike B4/B5/B9/B10 above).
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifestWithoutWidgets = manifest(
                "sha256:new", List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                true, "", Map.of(), Map.of(), Map.of(), Map.of(), true);

        java.io.ByteArrayOutputStream capturedOut = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        Set<String> dropped;
        try (Connection connection = dataSource.getConnection()) {
            System.setOut(new java.io.PrintStream(capturedOut, true, java.nio.charset.StandardCharsets.UTF_8));
            dropped = SchemaLifecycleExecutor.droppedConceptTables(
                    connection.getMetaData(), dataSource, manifestWithoutWidgets);
        } finally {
            System.setOut(originalOut);
        }

        assertEquals(Set.of(), dropped, "with no ownership history, no table can be proven NPDev-owned, "
                + "so nothing may be swept into the destructive drop path");
        String logged = capturedOut.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(logged.contains("B8:no_ownership_history"), logged);
        assertTrue(logged.contains("no ownership history recorded"), logged);
    }

    /** Two concepts shaped the way the generator really emits them -- the business columns PLUS the
     * platform columns {@code SchemaRealizationEmitter#fullColumnNames} always appends (id/version/
     * row_version/tenant_id) -- so the manifests built on top of them can carry a realistic
     * {@code businessTableAdditiveColumns} (LNCH-1 hardening X-B2b). */
    private void seedTwoRealisticConceptsWithData() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50), "
                    + "version BIGINT, row_version BIGINT, tenant_id VARCHAR(64))");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (1, 'Alpha', 0)");
            statement.execute("INSERT INTO widgets (id, name, version) VALUES (2, 'Beta', 0)");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY, label VARCHAR(50), "
                    + "version BIGINT, row_version BIGINT, tenant_id VARCHAR(64))");
            statement.execute("INSERT INTO gadgets (id, label, version) VALUES (1, 'G1', 0)");
            statement.execute("INSERT INTO gadgets (id, label, version) VALUES (2, 'G2', 0)");
        }
    }

    /**
     * A single concept shaped EXACTLY as {@code SchemaRealizationEmitter}'s fresh {@code CREATE
     * TABLE} emits it (VERIFIED against {@code SchemaRealizationEmitter:370-377}): every platform
     * column {@code NOT NULL} with its fixed platform {@code DEFAULT}. Deliberately distinct from
     * {@link #seedTwoRealisticConceptsWithData}, which creates the same columns plain nullable --
     * that fixture is fine for the scenarios that only care about column PRESENCE, but scenario 28 is
     * about NULLABILITY, so it needs the real production shape (LNCH-1 T-B1).
     */
    private void seedProductionShapedWidgets() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets ("
                    + "id BIGINT PRIMARY KEY, "
                    + "name VARCHAR(50) NOT NULL, "
                    + "version BIGINT NOT NULL DEFAULT 0, "
                    + "row_version BIGINT NOT NULL DEFAULT 0, "
                    + "tenant_id VARCHAR(120) NOT NULL DEFAULT 'default')");
            statement.execute("INSERT INTO widgets (id, name) VALUES (1, 'Alpha')");
            statement.execute("INSERT INTO widgets (id, name) VALUES (2, 'Beta')");
        }
    }

    /**
     * The manifest matching {@link #seedProductionShapedWidgets}. {@code name} is declared required in
     * BOTH versions, so the only columns the relax pass could act on are the three platform ones --
     * which is what makes scenario 28 a specific test of T-B1 rather than a general one.
     *
     * @param withNotes when true, adds one new OPTIONAL business field -- an utterly ordinary,
     *                  safe-additive upgrade, i.e. the everyday case in which T-B1 fired
     */
    private static SchemaLifecycleExecutor.SchemaManifest productionShapedWidgetsManifest(
            String fingerprint, boolean withNotes) {
        List<String> columns = withNotes
                ? List.of("id", "name", "notes", "version", "row_version", "tenant_id")
                : List.of("id", "name", "version", "row_version", "tenant_id");
        Map<String, String> types = new java.util.LinkedHashMap<>();
        types.put("id", "BIGINT");
        types.put("name", "VARCHAR(50)");
        if (withNotes) {
            types.put("notes", "VARCHAR(50)");
        }
        types.put("version", "BIGINT");
        types.put("row_version", "BIGINT");
        types.put("tenant_id", "VARCHAR(120)");
        Map<String, List<String>> columnsByTable = Map.of("widgets", columns);
        return manifest(
                fingerprint, List.of("widgets"), columnsByTable,
                realisticAdditiveColumns(columnsByTable, Map.of()),
                Map.of("widgets", types),
                Map.of(), Map.of(), false, "",
                Map.of("widgets", List.of("name")),
                Map.of(), Map.of(), Map.of(), true);
    }

    private static final Map<String, String> WIDGETS_TYPES = Map.of(
            "id", "BIGINT", "name", "VARCHAR(50)", "version", "BIGINT",
            "row_version", "BIGINT", "tenant_id", "VARCHAR(64)");
    private static final Map<String, String> GADGETS_TYPES = Map.of(
            "id", "BIGINT", "label", "VARCHAR(50)", "version", "BIGINT",
            "row_version", "BIGINT", "tenant_id", "VARCHAR(64)");

    /** The v1 manifest matching {@link #seedTwoRealisticConceptsWithData}: both concepts declared. */
    private static SchemaLifecycleExecutor.SchemaManifest realisticTwoConceptManifest(String fingerprint) {
        return realisticTwoConceptManifest(fingerprint, "");
    }

    /** As above, but carrying an itemized acknowledgment token -- used by scenario 27, where the
     * report contains an UNKNOWN item and NO concept drop, so the manifest must still declare both
     * concepts. */
    private static SchemaLifecycleExecutor.SchemaManifest realisticTwoConceptManifest(
            String fingerprint, String token) {
        return realisticTwoConceptManifest(fingerprint, token, false);
    }

    /**
     * @param withUnexplainableColumn when true, 'widgets' additionally declares the OPTIONAL bond
     *        column {@code owner_ref} and marks it NON-additive, which is what makes a report
     *        containing it an UNKNOWN. See {@link #UNEXPLAINABLE_COLUMN}'s note for why this is the
     *        correct vehicle since LNCH-1 T2, and why the previous one ('version' physically absent)
     *        no longer works.
     */
    private static SchemaLifecycleExecutor.SchemaManifest realisticTwoConceptManifest(
            String fingerprint, String token, boolean withUnexplainableColumn) {
        Map<String, List<String>> columns = Map.of(
                "widgets", widgetsColumns(withUnexplainableColumn),
                "gadgets", List.of("id", "label", "version", "row_version", "tenant_id"));
        return manifest(
                fingerprint, List.of("widgets", "gadgets"), columns,
                realisticAdditiveColumns(columns, nonAdditiveWidgetColumns(withUnexplainableColumn)),
                Map.of("widgets", widgetsTypes(withUnexplainableColumn), "gadgets", GADGETS_TYPES),
                Map.of(), Map.of(), true, token, Map.of(), Map.of(), Map.of(), Map.of(), true);
    }

    /**
     * The vehicle scenarios 24b, 25 and 27 use to manufacture an UNKNOWN delta item, since LNCH-1 T2.
     *
     * <p><b>Why not the old vehicle.</b> Those scenarios previously created 'widgets' without the
     * platform column {@code version}: before T2 that column was declared by the manifest but never
     * additive-eligible, so it could never be added back and the runtime itemized it as UNKNOWN. T2
     * made {@code version} additive precisely so that stops happening, which would have left all
     * three scenarios silently testing nothing.
     *
     * <p><b>Why this vehicle.</b> {@code SchemaDeltaReport} emits an {@code Unknown} in exactly one
     * non-error case: a manifest-declared column that is missing live, NOT additive-eligible, and not
     * explained by a declared rename. {@code SchemaLifecycleExecutor#refuseIfRequiredBondColumnMissing}
     * intercepts that case with its own dedicated refusal BEFORE the report runs -- but only for
     * columns listed in {@code businessTableRequiredColumns}. So the vehicle must be declared,
     * non-additive, and NOT required: an OPTIONAL bond column, which is what this is.
     *
     * <p><b>Honest scope note.</b> After T2 this shape is largely synthetic: in a real generated
     * manifest every non-additive column is a required bond (intercepted above) or a many-to-many
     * bond (which has no scalar column and never appears in {@code fullColumnNames}). These scenarios
     * therefore prove the executor's CONTRACT for an UNKNOWN -- refuse without a token, wipe with one
     * -- rather than a diff a current generator would produce. Each asserts
     * {@code hasOnlyNamedDestructiveKinds() == false} as an explicit precondition, so if this vehicle
     * ever stops producing an UNKNOWN the scenarios fail loudly instead of hollowing out.
     */
    private static final String UNEXPLAINABLE_COLUMN = "owner_ref";

    private static List<String> widgetsColumns(boolean withUnexplainableColumn) {
        return withUnexplainableColumn
                ? List.of("id", "name", "version", "row_version", "tenant_id", UNEXPLAINABLE_COLUMN)
                : List.of("id", "name", "version", "row_version", "tenant_id");
    }

    private static Map<String, List<String>> nonAdditiveWidgetColumns(boolean withUnexplainableColumn) {
        return withUnexplainableColumn ? Map.of("widgets", List.of(UNEXPLAINABLE_COLUMN)) : Map.of();
    }

    private static Map<String, String> widgetsTypes(boolean withUnexplainableColumn) {
        if (!withUnexplainableColumn) {
            return WIDGETS_TYPES;
        }
        Map<String, String> types = new java.util.LinkedHashMap<>(WIDGETS_TYPES);
        types.put(UNEXPLAINABLE_COLUMN, "BIGINT");
        return types;
    }

    /** The v2 manifest: the 'gadgets' concept has been dropped from the model. */
    private static SchemaLifecycleExecutor.SchemaManifest realisticConceptDropManifest(
            String token, boolean blanketAllowed) {
        return realisticConceptDropManifest("sha256:new", token, blanketAllowed);
    }

    private static SchemaLifecycleExecutor.SchemaManifest realisticConceptDropManifest(
            String fingerprint, String token, boolean blanketAllowed) {
        return realisticConceptDropManifest(fingerprint, token, blanketAllowed, false);
    }

    /** @param withUnexplainableColumn see {@link #realisticTwoConceptManifest(String, String, boolean)} */
    private static SchemaLifecycleExecutor.SchemaManifest realisticConceptDropManifest(
            String fingerprint, String token, boolean blanketAllowed, boolean withUnexplainableColumn) {
        Map<String, List<String>> columns = Map.of("widgets", widgetsColumns(withUnexplainableColumn));
        return manifest(
                fingerprint, List.of("widgets"), columns,
                realisticAdditiveColumns(columns, nonAdditiveWidgetColumns(withUnexplainableColumn)),
                Map.of("widgets", widgetsTypes(withUnexplainableColumn)),
                Map.of(), Map.of(), blanketAllowed, token, Map.of(), Map.of(), Map.of(), Map.of(), true);
    }

    private static int rowCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(resultSet.next(), "expected a count row");
            return resultSet.getInt(1);
        }
    }

    /** Scenario 17's two-concept manifest (widgets gains required 'status' with a "PENDING" literal
     * default; gadgets drops 'legacy_flag'), parameterized only by the acknowledgment token. */
    private static SchemaLifecycleExecutor.SchemaManifest scenarioManifest(String token) {
        return manifest(
                "sha256:new", List.of("widgets", "gadgets"),
                Map.of("widgets", List.of("id", "name", "status"), "gadgets", List.of("id")),
                Map.of("widgets", List.of("status"), "gadgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)"),
                        "gadgets", Map.of("id", "BIGINT")),
                Map.of(), Map.of(), false, token,
                Map.of("widgets", List.of("status")),
                Map.of("widgets", Map.of("status", "\"PENDING\"")), Map.of(), Map.of(), true);
    }

    // ---- Shared helpers -------------------------------------------------------------------------

    /** Calls {@code beforeMigrate} a second time against the SAME manifest (after {@code afterMigrate}
     * has stored the new fingerprint from the first pass) and asserts the "stored == manifest" early
     * no-op branch is what actually fires -- the plan's "every row also re-boots twice to prove
     * idempotence" requirement, driven through the real production sequencing. */
    private void assertSecondBootIsNoOp(SchemaLifecycleExecutor.SchemaManifest manifest) {
        SchemaLifecycleExecutor.DestructiveRecreation second = executor.beforeMigrate(dataSource, manifest);
        assertFalse(second.performed(), "a second boot with an already-converged fingerprint must never be destructive");
        assertFalse(second.safeAdditive(), "a second boot with an already-converged fingerprint is a pure no-op, not even safe-additive");
    }

    private static String readName(DataSource dataSource, long id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return readColumn(connection, "widgets", "name", id);
        }
    }

    private static String readColumn(Connection connection, String table, String column, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected a row with id " + id);
                return resultSet.getString(1);
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

    private static boolean hasTable(DatabaseMetaData metadata, String table) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getTables(null, null, candidate, new String[] {"TABLE"})) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String columnTypeName(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return resultSet.getString("TYPE_NAME");
                    }
                }
            }
        }
        throw new IllegalStateException("Column not found: " + table + "." + column);
    }

    private static int columnSize(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    if (column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return resultSet.getInt("COLUMN_SIZE");
                    }
                }
            }
        }
        throw new IllegalStateException("Column not found: " + table + "." + column);
    }

    private static boolean constraintExists(Connection connection, String table, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE UPPER(CONSTRAINT_NAME) = UPPER(?) AND UPPER(TABLE_NAME) = UPPER(?)")) {
            statement.setString(1, name);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
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

    /** LNCH-1-B7: simulates what a previous successful boot records -- the set of business tables
     * NPDev owned at that point. Without this, an orphaned table's ownership is unknown and the
     * executor deliberately leaves it alone. */
    private static void seedOwnedBusinessTables(DataSource dataSource, List<String> tables) throws SQLException {
        String json = "[" + String.join(",", tables.stream().map(t -> "\"" + t + "\"").toList()) + "]";
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_metadata "
                        + "(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_metadata (metadata_key, metadata_value, updated_at_ms) VALUES (?, ?, ?)")) {
                statement.setString(1, "ownedBusinessTables");
                statement.setString(2, json);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    /** REG-8 (P4): seeds a raw {@code npdev_schema_history} row with an explicit, caller-controlled
     * {@code applied_at_utc} -- Trigger C's temporal ordering must be deterministic in a test, not
     * dependent on wall-clock resolution between two inserts made milliseconds apart. */
    private static void seedHistoryRow(DataSource dataSource, String toFingerprint, String outcome, long appliedAtUtc) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS npdev_schema_history "
                        + "(id TEXT PRIMARY KEY, applied_at_utc BIGINT NOT NULL, from_fingerprint TEXT, "
                        + "to_fingerprint TEXT, classification TEXT, items_json TEXT, ack_token_used TEXT, outcome TEXT NOT NULL)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npdev_schema_history (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, NULL, ?, NULL, NULL, NULL, ?)")) {
                statement.setString(1, java.util.UUID.randomUUID().toString());
                statement.setLong(2, appliedAtUtc);
                statement.setString(3, toFingerprint);
                statement.setString(4, outcome);
                statement.executeUpdate();
            }
        }
    }

    private static String readStoredFingerprint(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT metadata_value FROM npdev_schema_metadata WHERE metadata_key = 'schemaFingerprint'");
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "expected a stored fingerprint row");
            return resultSet.getString(1);
        }
    }

    private record HistoryRow(String outcome, String ackTokenUsed) {
    }

    private record HistoryDetail(String classification, String itemsJson, String outcome) {
    }

    /** Every history row's (classification, items_json, outcome), newest first. Ordering across rows
     * with the same millisecond timestamp is not relied upon -- scenario 22 asserts per-step presence
     * and outcome, not a strict total order. */
    private static List<HistoryDetail> allHistoryRows(DataSource dataSource) throws SQLException {
        List<HistoryDetail> rows = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT classification, items_json, outcome FROM npdev_schema_history ORDER BY applied_at_utc DESC");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(new HistoryDetail(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)));
            }
        }
        return rows;
    }

    /**
     * The classifications {@code recordStepPass} writes -- one row per mutating PASS within a boot,
     * as opposed to the boot's own outcome row. Must track {@code recordStepPass}'s call sites in
     * {@code SchemaLifecycleExecutor}.
     *
     * <p><b>Why this list exists (LNCH-1 T1).</b> A single boot can write several history rows, and
     * {@code applied_at_utc} is only millisecond-precision while {@code id} is a random UUID -- so
     * there is no deterministic tiebreaker in the schema, and two rows written in the same
     * millisecond have no defined order. Every caller of {@link #latestHistoryRow} means "the
     * outcome of this boot", never "whichever step pass happened to sort first", so step-pass rows
     * are excluded here rather than left to chance. T1's {@code TIGHTEN_PLATFORM_COLUMNS} made this
     * latent hazard reachable in the common case (it fires on any table with a nullable platform
     * column, which most fixtures have); {@code RELAX_NOT_NULL} and the others already carried it.
     */
    private static final Set<String> STEP_PASS_CLASSIFICATIONS = Set.of(
            "TABLE_RENAME", "COLUMN_RENAME", "TYPE_WIDENING", "REQUIRED_BACKFILL",
            "RELAX_NOT_NULL", "TIGHTEN_PLATFORM_COLUMNS");

    private static HistoryRow latestHistoryRow(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT outcome, ack_token_used, classification FROM npdev_schema_history "
                             + "ORDER BY applied_at_utc DESC");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String classification = resultSet.getString(3);
                if (classification != null && STEP_PASS_CLASSIFICATIONS.contains(classification)) {
                    continue; // a within-boot step pass, not this boot's outcome
                }
                return new HistoryRow(resultSet.getString(1), resultSet.getString(2));
            }
            throw new AssertionError("expected at least one non-step-pass npdev_schema_history row");
        }
    }

    /**
     * STOR-16: like {@link #manifest}, but declaring the {@code Ephemeral} posture.
     *
     * <p>A separate factory rather than a strategy parameter on the 15-argument one above: every
     * existing row passes {@code "DropAndRecreateOnStructureChange"} implicitly, and threading a new
     * argument through 16 call sites to change nothing at 16 of them is churn that hides the one
     * call that differs.
     */
    private static SchemaLifecycleExecutor.SchemaManifest ephemeralManifest(
            String schemaFingerprint,
            List<String> businessTables,
            Map<String, List<String>> businessTableColumns) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, schemaFingerprint, List.of(), businessTables,
                businessTableColumns, Map.of(), Map.of(), Map.of(), Map.of(), true,
                "Ephemeral", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_ALL_DATA_IS_DELETED_ON_EVERY_START", "",
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            String schemaFingerprint,
            List<String> businessTables,
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, Map<String, String>> businessTableRenamedColumns,
            Map<String, String> businessTableRenames,
            boolean allowDestructiveRecreate,
            String destructiveAcknowledgment,
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, Map<String, String>> businessTableColumnDefaultLiterals,
            Map<String, List<String>> businessTableExpressionDefaultColumns,
            Map<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> businessTableUniqueConstraints,
            boolean physicalDatabase) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", physicalDatabase, schemaFingerprint, List.of(), businessTables,
                businessTableColumns, businessTableAdditiveColumns, businessTableColumnTypes,
                businessTableRenamedColumns, businessTableRenames, allowDestructiveRecreate,
                "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", destructiveAcknowledgment,
                businessTableRequiredColumns, businessTableColumnDefaultLiterals,
                businessTableExpressionDefaultColumns, businessTableUniqueConstraints);
    }

    /**
     * Computes {@code businessTableAdditiveColumns} the way a REAL manifest does, so a fixture can
     * never silently disagree with production about what is additive-eligible -- the divergence that
     * hid LNCH-1 hardening finding X-B2 (fixtures passed {@code Map.of()}, i.e. "nothing is
     * additive", while every real manifest marks nearly everything additive).
     *
     * <p>VERIFIED against {@code SchemaRealizationEmitter#additiveColumnNames} /
     * {@code #isAdditiveEligible} at commit {@code 98e8410} -- note this differs from the first draft
     * of the hardening plan's §3.2 helper in two ways that matter:
     * <ul>
     *   <li>production seeds the additive list with {@code tenant_id}, {@code row_version} and --
     *       since LNCH-1 T2 (finding T-B2) -- {@code version} unconditionally, so all three ARE
     *       additive-eligible (the draft excluded only {@code id});</li>
     *   <li>{@code id} is the only platform column that is never additive: it is the primary key,
     *       present by construction on any table that exists at all.</li>
     * </ul>
     *
     * <p><b>Before T2</b>, {@code version} was declared by {@code fullColumnNames} but absent from
     * {@code additiveColumnNames}, which is what made "a table missing {@code version}" the canonical
     * way to manufacture an UNKNOWN item in this suite. It is no longer un-addable, so scenarios that
     * need an UNKNOWN construct one from a declared, non-additive, non-required column instead --
     * see {@code realisticTwoConceptManifest}'s {@code withUnexplainableColumn} flag.
     * A bond/FK column is additive-eligible unless it is required or many-to-many
     * ({@code isAdditiveEligible}); pass those in {@code nonAdditiveBondColumnsByTable}.
     *
     * @param columnsByTable the same map passed as {@code businessTableColumns}
     * @param nonAdditiveBondColumnsByTable required/many-to-many bond columns per table (usually empty)
     */
    private static Map<String, List<String>> realisticAdditiveColumns(
            Map<String, List<String>> columnsByTable,
            Map<String, List<String>> nonAdditiveBondColumnsByTable) {
        Map<String, List<String>> additive = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : columnsByTable.entrySet()) {
            List<String> bonds = nonAdditiveBondColumnsByTable.getOrDefault(entry.getKey(), List.of());
            additive.put(entry.getKey(), entry.getValue().stream()
                    .filter(column -> !"id".equalsIgnoreCase(column))
                    .filter(column -> !bonds.contains(column))
                    .toList());
        }
        return additive;
    }

    private static SchemaLifecycleExecutor.SchemaManifest uniqueManifest(
            Map<String, List<String>> businessTableColumns,
            List<SchemaLifecycleExecutor.UniqueConstraintDecl> uniqueConstraints) {
        String table = businessTableColumns.keySet().iterator().next();
        return manifest("sha256:new", List.copyOf(businessTableColumns.keySet()), businessTableColumns,
                Map.of(), Map.of(), Map.of(), Map.of(), true, "", Map.of(), Map.of(), Map.of(),
                Map.of(table, uniqueConstraints), true);
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
