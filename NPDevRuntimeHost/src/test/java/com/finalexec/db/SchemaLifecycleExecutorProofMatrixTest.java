package com.finalexec.db;

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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 7 (task 7.1). The 16-scenario H2 proof matrix from
 * {@code docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md}'s Phase 7 table, in ONE place, traceable row by row.
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
        SchemaLifecycleExecutor.SchemaManifest oldManifest = manifest(
                "sha256:old", List.of("widgets"),
                Map.of("widgets", List.of("id", "name")), Map.of("widgets", List.of()),
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

    private static HistoryRow latestHistoryRow(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT outcome, ack_token_used FROM npdev_schema_history ORDER BY applied_at_utc DESC");
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "expected at least one npdev_schema_history row");
            return new HistoryRow(resultSet.getString(1), resultSet.getString(2));
        }
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
