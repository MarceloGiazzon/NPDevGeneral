package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

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
 * LNCH-1 Phase 7 (task 7.2). The Postgres Testcontainers twin of the H2 proof matrix
 * ({@link SchemaLifecycleExecutorProofMatrixTest}), following the LIGHTWEIGHT bare-JDBC pattern
 * {@code postgres-test-support}'s {@code PostgresTestSupport} already established for 6 kernel
 * adapter test suites (a single reused {@link PostgreSQLContainer}, {@code postgres:15-alpine},
 * {@code withReuse(true)}) -- NOT the heavier {@code @SpringBootTest} pattern
 * {@code AbstractScenarioIntegrationTest} uses. {@code postgres-test-support} itself is a separate
 * Gradle module RuntimeHost's generated-app-standalone build cannot depend on (RuntimeHost's own
 * dependencies are all Maven-coordinate or jar-staged, never intra-workspace {@code project(...)}
 * references -- see {@code build.gradle.template}), so the container-lifecycle logic is
 * self-contained here, matching how every H2 test in this package already duplicates its own
 * {@code SingleConnectionUrlDataSource} rather than sharing one.
 *
 * <p><b>Scope (per the plan's own framing across Phases 1-5, "H2-required, Postgres-nightly"):
 * only the DDL-executing, engine-dialect-sensitive scenarios are twinned here</b> -- pure-logic
 * unit tests ({@code TypeChangeMatrixTest}, {@code RenameResolutionTest}, {@code SchemaDeltaReportTest}'s
 * item-vocabulary assertions) never touch a real database and gain nothing from a second engine.
 * Covers: field rename (Postgres {@code RENAME COLUMN} dialect branch), concept rename, type
 * widening (INT-&gt;BIGINT -- closes Phase 3's open "does {@code ALTER COLUMN ... TYPE} need a
 * {@code USING} clause on Postgres" question for this specific pair, confirmed NOT needed below),
 * surgical destructive drop with a matching ack token, unique-constraint pre-check+apply on clean
 * data, required-field literal-default backfill, and a simulated crash-mid-destructive residue
 * (same construction as the H2 matrix's row 14 second proof).
 *
 * <p><b>Docker/Windows npipe config is already handled by {@code build.gradle.template}'s
 * {@code integrationTest} task registration</b> (sets {@code DOCKER_HOST}/{@code docker.host}/
 * {@code docker.client.strategy} for the forked test JVM before any test code runs), so this class
 * does not duplicate {@code PostgresTestSupport}'s {@code resolveDockerHostConfiguration} logic.
 *
 * <p>One container is shared across every {@code @Test} in this class (module-scoped via a static
 * field, {@code withReuse(true)}); per-test isolation comes from a unique table-name suffix per
 * test method, not per-test containers or schemas -- {@code SchemaLifecycleExecutor}'s live-DB
 * introspection ({@code DatabaseMetaData#getTables}/{@code #getColumns}) is not
 * {@code search_path}-scoped, so schema-per-test would not actually isolate it; unique table names
 * do.
 */
@Tag("integration")
class SchemaLifecycleExecutorPostgresProofMatrixTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("npdev_runtimehost_proof_matrix")
            .withUsername("npdev")
            .withPassword("npdev")
            .withReuse(true);

    private final SchemaLifecycleExecutor executor = new SchemaLifecycleExecutor();
    private DataSource dataSource;
    private String suffix;

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainerUnlessReused() {
        // withReuse(true): deliberately NOT calling POSTGRES.stop() here -- Testcontainers' reuse
        // mechanism (when the host opts in via ~/.testcontainers.properties) keeps a reusable
        // container alive across JVM runs on purpose; stopping it here would defeat that.
    }

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionUrlDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        suffix = "s" + Math.abs(System.nanoTime() % 1_000_000_000L);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement listStatement = connection.createStatement();
             ResultSet resultSet = listStatement.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = 'public' AND table_name LIKE '%" + suffix + "%'")) {
            List<String> tables = new java.util.ArrayList<>();
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
            try (Statement dropStatement = connection.createStatement()) {
                for (String table : tables) {
                    dropStatement.execute("DROP TABLE IF EXISTS \"" + table + "\" CASCADE");
                }
            }
        }
    }

    private String table(String base) {
        return base + "_" + suffix;
    }

    // ---- Field rename (twins H2 matrix row 4) ----------------------------------------------------

    @Test
    @DisplayName("Postgres: field rename applies via RENAME COLUMN, data intact")
    void fieldRenameAppliesInPlaceOnPostgres() throws SQLException {
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + widgets + " (id BIGINT PRIMARY KEY, old_label VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO " + widgets + " (id, old_label, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "new_label", "version")),
                Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "new_label", "VARCHAR(50)", "version", "BIGINT")),
                Map.of(widgets, Map.of("new_label", "old_label")), Map.of(), true, "",
                Map.of(), Map.of(), Map.of(), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "the rename must resolve safely on Postgres, not destructively");
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, widgets, "new_label"));
            assertFalse(hasColumn(metadata, widgets, "old_label"));
            assertEquals("alpha", readColumn(connection, widgets, "new_label", 1L), "data must survive the Postgres RENAME COLUMN");
        }

        executor.afterMigrate(dataSource, manifest);
        SchemaLifecycleExecutor.DestructiveRecreation second = executor.beforeMigrate(dataSource, manifest);
        assertFalse(second.performed());
        assertFalse(second.safeAdditive(), "second boot must be a pure no-op");
    }

    // ---- Concept rename (twins H2 matrix row 5) -------------------------------------------------

    @Test
    @DisplayName("Postgres: concept (table) rename applies via ALTER TABLE ... RENAME TO, data intact")
    void conceptRenameAppliesInPlaceOnPostgres() throws SQLException {
        String gadgets = table("gadgets");
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + gadgets + " (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO " + gadgets + " (id, name, version) VALUES (1, 'alpha', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "name", "version")),
                Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "name", "VARCHAR(50)", "version", "BIGINT")),
                Map.of(), Map.of(widgets, gadgets), true, "",
                Map.of(), Map.of(), Map.of(), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive());
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasTable(metadata, widgets));
            assertFalse(hasTable(metadata, gadgets));
            assertEquals("alpha", readColumn(connection, widgets, "name", 1L), "data must survive the Postgres table rename");
        }
    }

    // ---- Type widening (twins H2 matrix row 7; closes Phase 3's USING-clause question) -----------

    @Test
    @DisplayName("Postgres: INT->BIGINT widening applies via ALTER COLUMN ... TYPE with no USING clause needed")
    void intToBigintWideningAppliesOnPostgresWithoutAUsingClause() throws SQLException {
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + widgets + " (id BIGINT PRIMARY KEY, quantity INTEGER)");
            statement.execute("INSERT INTO " + widgets + " (id, quantity) VALUES (1, " + Integer.MAX_VALUE + ")");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "quantity")),
                Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "quantity", "BIGINT")),
                Map.of(), Map.of(), true, "",
                Map.of(), Map.of(), Map.of(), Map.of());

        // Confirms Phase 3's open question directly: executeWidenColumnType emits a bare
        // "ALTER TABLE ... ALTER COLUMN ... TYPE BIGINT" for Postgres, no USING clause -- if
        // Postgres required one for int->bigint, this call would throw here.
        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "int->bigint must widen cleanly on Postgres with the current (no-USING-clause) DDL");
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals("int8", columnTypeName(metadata, widgets, "quantity"), "Postgres reports BIGINT as int8");
            assertEquals(Integer.MAX_VALUE, Long.parseLong(readColumn(connection, widgets, "quantity", 1L)),
                    "the boundary value must survive the widening exactly");
        }
    }

    // ---- VARCHAR length widening (found-live gap: only the integer family had been proven on a real
    // Postgres server; TypeChangeMatrix.classifySameFamily's VARCHAR branch is a structurally
    // different comparison -- parameter-only, same base type -- from the integer-chain rank compare
    // intToBigintWideningAppliesOnPostgresWithoutAUsingClause exercises, so it needed its own proof,
    // not an inference from that one. executeWidenColumnType's DDL is a single, type-pair-agnostic
    // code path (no per-pair branching, only per-engine), so one representative pair per distinct
    // TypeChangeMatrix comparison branch is the right granularity, not exhaustive pair coverage. ----

    @Test
    @DisplayName("Postgres: VARCHAR(n) length widening applies via ALTER COLUMN ... TYPE with no USING clause needed")
    void varcharLengthWideningAppliesOnPostgresWithoutAUsingClause() throws SQLException {
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + widgets + " (id BIGINT PRIMARY KEY, name VARCHAR(20))");
            statement.execute("INSERT INTO " + widgets + " (id, name) VALUES (1, '12345678901234567890')"); // exactly 20 chars, the boundary
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "name")),
                Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "name", "VARCHAR(80)")),
                Map.of(), Map.of(), true, "",
                Map.of(), Map.of(), Map.of(), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "VARCHAR(20)->VARCHAR(80) must widen cleanly on Postgres with the current (no-USING-clause) DDL");
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            assertEquals("12345678901234567890", readColumn(connection, widgets, "name", 1L),
                    "the boundary-length value must survive the widening exactly");
        }
    }

    // ---- NUMERIC precision widening (same rationale as the VARCHAR case above: classifySameFamily's
    // NUMERIC branch is its own distinct comparison, unproven on a real Postgres server before this). --

    @Test
    @DisplayName("Postgres: NUMERIC(p,s) precision widening applies via ALTER COLUMN ... TYPE with no USING clause needed")
    void numericPrecisionWideningAppliesOnPostgresWithoutAUsingClause() throws SQLException {
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + widgets + " (id BIGINT PRIMARY KEY, price NUMERIC(6,2))");
            statement.execute("INSERT INTO " + widgets + " (id, price) VALUES (1, 1234.56)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "price")),
                Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "price", "NUMERIC(12,2)")),
                Map.of(), Map.of(), true, "",
                Map.of(), Map.of(), Map.of(), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "NUMERIC(6,2)->NUMERIC(12,2) must widen cleanly on Postgres with the current (no-USING-clause) DDL");
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, new java.math.BigDecimal("1234.56").compareTo(new java.math.BigDecimal(readColumn(connection, widgets, "price", 1L))),
                    "the precise decimal value must survive the widening exactly");
        }
    }

    // ---- NOT NULL relaxation (LNCH-1 open-items audit find, 2026-07-19: a field going from required
    // to optional changes the schema fingerprint, but classify() has no nullability awareness at all,
    // so the live constraint was silently NEVER relaxed -- see relaxNoLongerRequiredColumns' javadoc). --

    @Test
    @DisplayName("Postgres: field no longer required has its NOT NULL constraint relaxed, data intact")
    void columnNoLongerRequiredIsRelaxedOnPostgres() throws SQLException {
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + widgets + " (id BIGINT PRIMARY KEY, nickname VARCHAR(50) NOT NULL)");
            statement.execute("INSERT INTO " + widgets + " (id, nickname) VALUES (1, 'Alpha')");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        // nickname was required in the old model; the new one no longer lists it as required --
        // same name, same type, only nullability differs.
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "nickname")),
                Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "nickname", "VARCHAR(50)")),
                Map.of(), Map.of(), true, "",
                Map.of(widgets, List.of("id")), Map.of(), Map.of(), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive(), "a pure nullability relaxation must resolve via the safe-additive path");
        assertFalse(result.performed());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(isNotNull(metadata, widgets, "nickname"), "the column must no longer be NOT NULL on Postgres");
            assertEquals("Alpha", readColumn(connection, widgets, "nickname", 1L), "existing data must survive untouched");
            try (PreparedStatement update = connection.prepareStatement("UPDATE " + widgets + " SET nickname = NULL WHERE id = 1")) {
                update.executeUpdate();
            }
            assertEquals(null, readColumn(connection, widgets, "nickname", 1L), "the relaxed column must genuinely accept NULL on Postgres");
        }
    }

    // ---- Surgical destructive drop with matching ack (twins H2 matrix row 9) ----------------------

    @Test
    @DisplayName("Postgres: drop field with matching ack is surgical, sibling data intact")
    void dropColumnWithMatchingAckIsSurgicalOnPostgres() throws SQLException {
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + widgets + " (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO " + widgets + " (id, name, legacy_flag) VALUES (1, 'Alpha', TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "name")), Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of());
        String expectedToken = DestructiveAckToken.compute("sha256:new",
                SchemaDeltaReport.generate(dataSource, manifestWithoutToken).stableStrings());

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "name")), Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, expectedToken, Map.of(), Map.of(), Map.of(), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.performed());
        assertFalse(result.safeAdditive());
        assertEquals(List.of(widgets), result.droppedTables());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, widgets, "legacy_flag"), "the dropped column must be gone");
            assertTrue(hasColumn(metadata, widgets, "name"), "a sibling column must survive");
            assertEquals("Alpha", readColumn(connection, widgets, "name", 1L), "the row must survive, minus the dropped column");
        }
        HistoryRow row = latestHistoryRow(dataSource, widgets);
        assertEquals("APPLIED", row.outcome());
    }

    // ---- Rename + unrelated acknowledged drop on the SAME table (LNCH-1 Phase 7 rehearsal find) ---

    @Test
    @DisplayName("Postgres: rename + unrelated acknowledged drop on the SAME table -- rename applies "
            + "in place (data preserved), drop applies surgically")
    void renamePlusUnrelatedAcknowledgedDropOnSameTableBothApplyOnPostgres() throws SQLException {
        // Reproduces, against a real Postgres server, the exact silent data-loss bug found live
        // during the Phase 7 compose-stack rehearsal: a table with BOTH a declared rename AND an
        // unrelated, separately-acknowledged column drop used to classify straight to DESTRUCTIVE,
        // skipping the (perfectly safe) rename entirely -- the old column's data was left orphaned
        // while the new column was added empty by the additive migration. No error, no refusal, a
        // "successful" boot with quietly lost data.
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + widgets + " (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO " + widgets + " (id, name, legacy_flag) VALUES (1, 'Alpha', TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "title")), Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "title", "VARCHAR(50)")),
                Map.of(widgets, Map.of("title", "name")), Map.of(), false, "",
                Map.of(), Map.of(), Map.of(), Map.of());
        String expectedToken = DestructiveAckToken.compute("sha256:new",
                SchemaDeltaReport.generate(dataSource, manifestWithoutToken).stableStrings());

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "title")), Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "title", "VARCHAR(50)")),
                Map.of(widgets, Map.of("title", "name")), Map.of(), false, expectedToken,
                Map.of(), Map.of(), Map.of(), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.performed());
        assertFalse(result.safeAdditive());

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertFalse(hasColumn(metadata, widgets, "name"), "the old column name must be gone (renamed away)");
            assertTrue(hasColumn(metadata, widgets, "title"), "the new column name must be present");
            assertFalse(hasColumn(metadata, widgets, "legacy_flag"), "the unrelated acknowledged drop must still apply");
            assertEquals("Alpha", readColumn(connection, widgets, "title", 1L),
                    "the renamed column's data must be PRESERVED, not orphaned under the old name or empty under the new one");
        }
        HistoryRow row = latestHistoryRow(dataSource, widgets);
        assertEquals("APPLIED", row.outcome());
    }

    // ---- Unique constraint, clean data (twins H2 matrix row 12) -----------------------------------

    @Test
    @DisplayName("Postgres: new unique constraint on clean data is applied and enforced")
    void newUniqueOnCleanDataIsAppliedOnPostgres() throws SQLException {
        String users = table("users");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + users + " (id BIGINT PRIMARY KEY, email VARCHAR(100), tenant_id VARCHAR(120), version BIGINT)");
            statement.execute("INSERT INTO " + users + " (id, email, tenant_id, version) VALUES (1, 'alice@x.com', 'acme', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        SchemaLifecycleExecutor.SchemaManifest manifest = uniqueManifest(users,
                Map.of(users, List.of("id", "email", "tenant_id", "version")),
                List.of(new SchemaLifecycleExecutor.UniqueConstraintDecl("ux_" + users + "_email", List.of("email"), true)));

        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(constraintExists(connection, users, "ux_" + users + "_email"), "a clean pass must apply the constraint");
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + users + " (id, email, tenant_id, version) VALUES (2, 'alice@x.com', 'acme', 1)")) {
                    insert.executeUpdate();
                }
            }, "the newly-applied constraint must actually reject a same-tenant duplicate email on Postgres");
        }

        // Idempotence: re-running must not error against an already-constrained table.
        executor.afterMigrate(dataSource, manifest);
    }

    // ---- Unique constraint already present as V1's bootstrap INDEX (LNCH-1 Phase 7 rehearsal find) --

    @Test
    @DisplayName("Postgres: unique already present as V1's bootstrap INDEX (not a constraint) -- "
            + "recognized as already-applied, no \"relation already exists\" crash")
    void uniqueAlreadyPresentAsBootstrapIndexIsRecognizedAsAppliedOnPostgres() throws SQLException {
        // Reproduces, against a REAL Postgres server, the exact crash found live during the Phase 7
        // compose-stack rehearsal: SchemaRealizationEmitter's V1 bootstrap DDL for an ordinary
        // (non-anchor) unique field is CREATE UNIQUE INDEX IF NOT EXISTS ux_<table>_<column> -- an
        // INDEX, never an ADD CONSTRAINT -- under the exact same name applyUniqueConstraints later
        // tries to ADD CONSTRAINT with. On Postgres, INFORMATION_SCHEMA.TABLE_CONSTRAINTS does not
        // list plain indexes, so without the indexExists() fallback this scenario throws
        // "ERROR: relation \"ux_...\" already exists" and crash-loops the app on first boot -- H2
        // silently tolerates the duplicate name, which is why the H2-only matrix never caught it.
        String users = table("users");
        String constraintName = "ux_" + users + "_email";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + users + " (id BIGINT PRIMARY KEY, email VARCHAR(100), tenant_id VARCHAR(120), version BIGINT)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + constraintName + " ON " + users + " (tenant_id, email)");
            statement.execute("INSERT INTO " + users + " (id, email, tenant_id, version) VALUES (1, 'alice@x.com', 'acme', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");
        SchemaLifecycleExecutor.SchemaManifest manifest = uniqueManifest(users,
                Map.of(users, List.of("id", "email", "tenant_id", "version")),
                List.of(new SchemaLifecycleExecutor.UniqueConstraintDecl(constraintName, List.of("email"), true)));

        executor.afterMigrate(dataSource, manifest);
        // Re-run for the same reason the clean-data scenario above does: converges without ever
        // attempting a duplicate ADD CONSTRAINT against the bootstrap index.
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + users + " (id, email, tenant_id, version) VALUES (2, 'alice@x.com', 'acme', 1)")) {
                    insert.executeUpdate();
                }
            }, "the pre-existing bootstrap index must still actually enforce uniqueness");
        }
    }

    // ---- Required-field literal-default backfill (twins H2 matrix row 2) --------------------------

    @Test
    @DisplayName("Postgres: required field with literal default backfills existing rows, then enforces NOT NULL")
    void requiredFieldBackfillOnPostgres() throws SQLException {
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + widgets + " (id BIGINT PRIMARY KEY, name VARCHAR(50), version BIGINT)");
            statement.execute("INSERT INTO " + widgets + " (id, name, version) VALUES (1, 'alpha', 1)");
            statement.execute("INSERT INTO " + widgets + " (id, name, version) VALUES (2, 'beta', 1)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "name", "status", "version")),
                Map.of(widgets, List.of("status")),
                Map.of(widgets, Map.of("id", "BIGINT", "name", "VARCHAR(50)", "status", "VARCHAR(50)", "version", "BIGINT")),
                Map.of(), Map.of(), true, "",
                Map.of(widgets, List.of("status")),
                Map.of(widgets, Map.of("status", "\"PENDING\"")), Map.of(), Map.of());

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertTrue(result.safeAdditive());
        assertFalse(result.performed());

        // R2 (F1): required-field backfill/refusal now runs at the single afterMigrate call site
        // (reached on EVERY boot path), not inside beforeMigrate's safe-additive branch.
        executor.afterMigrate(dataSource, manifest);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(hasColumn(metadata, widgets, "status"));
            assertTrue(isNotNull(metadata, widgets, "status"), "the column must be enforced NOT NULL after backfill on Postgres");
            assertEquals("PENDING", readColumn(connection, widgets, "status", 1L));
            assertEquals("PENDING", readColumn(connection, widgets, "status", 2L));
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + widgets + " (id, name, status, version) VALUES (3, 'gamma', NULL, 1)")) {
                    insert.executeUpdate();
                }
            }, "a NULL insert into the now-NOT-NULL column must be rejected by Postgres");
        }
    }

    // ---- Simulated crash-mid-destructive residue (twins H2 matrix row 14) -------------------------

    @Test
    @DisplayName("Postgres: residue left by a simulated mid-destructive crash converges cleanly on reboot")
    void simulatedMidDestructiveCrashConvergesOnPostgres() throws SQLException {
        String widgets = table("widgets");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + widgets + " (id BIGINT PRIMARY KEY, name VARCHAR(50), legacy_flag BOOLEAN)");
            statement.execute("INSERT INTO " + widgets + " (id, name, legacy_flag) VALUES (1, 'Alpha', TRUE)");
        }
        seedStoredFingerprint(dataSource, "sha256:old");

        SchemaLifecycleExecutor.SchemaManifest manifestWithoutToken = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "name")), Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, "", Map.of(), Map.of(), Map.of(), Map.of());
        String expectedToken = DestructiveAckToken.compute("sha256:new",
                SchemaDeltaReport.generate(dataSource, manifestWithoutToken).stableStrings());
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                "sha256:new", List.of(widgets),
                Map.of(widgets, List.of("id", "name")), Map.of(widgets, List.of()),
                Map.of(widgets, Map.of("id", "BIGINT", "name", "VARCHAR(50)")),
                Map.of(), Map.of(), false, expectedToken, Map.of(), Map.of(), Map.of(), Map.of());

        // Simulate exactly what a crash between "drop legacy_flag" and history-row commit would
        // leave: the DDL already applied live, no history row for THIS pass yet.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + widgets + " DROP COLUMN legacy_flag");
        }

        SchemaLifecycleExecutor.DestructiveRecreation result = executor.beforeMigrate(dataSource, manifest);
        assertFalse(result.performed(), "the column is already gone -- this must NOT be a second destructive pass");
        assertTrue(result.safeAdditive(), "the live DB already matches the manifest once the crashed drop is accounted for");

        try (Connection connection = dataSource.getConnection()) {
            assertEquals("Alpha", readColumn(connection, widgets, "name", 1L), "the row must survive the converged reboot");
        }
    }

    // ---- Shared helpers -------------------------------------------------------------------------

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
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM npdev_schema_metadata WHERE metadata_key = ?")) {
                delete.setString(1, "schemaFingerprint");
                delete.executeUpdate();
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

    private static HistoryRow latestHistoryRow(DataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT outcome FROM npdev_schema_history WHERE items_json LIKE ? ORDER BY applied_at_utc DESC")) {
            statement.setString(1, "%" + table + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected at least one npdev_schema_history row for " + table);
                return new HistoryRow(resultSet.getString(1));
            }
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
            Map<String, List<SchemaLifecycleExecutor.UniqueConstraintDecl>> businessTableUniqueConstraints) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                // "Postgres" (not "H2Local"/"H2Server") selects the Postgres dialect branch in
                // executeRenameColumn/executeWidenColumnType -- this is the entire point of this twin.
                "Postgres", "jdbc", true, schemaFingerprint, List.of(), businessTables,
                businessTableColumns, businessTableAdditiveColumns, businessTableColumnTypes,
                businessTableRenamedColumns, businessTableRenames, allowDestructiveRecreate,
                "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", destructiveAcknowledgment,
                businessTableRequiredColumns, businessTableColumnDefaultLiterals,
                businessTableExpressionDefaultColumns, businessTableUniqueConstraints);
    }

    private static SchemaLifecycleExecutor.SchemaManifest uniqueManifest(
            String table,
            Map<String, List<String>> businessTableColumns,
            List<SchemaLifecycleExecutor.UniqueConstraintDecl> uniqueConstraints) {
        return manifest("sha256:new", List.copyOf(businessTableColumns.keySet()), businessTableColumns,
                Map.of(), Map.of(), Map.of(), Map.of(), true, "", Map.of(), Map.of(), Map.of(),
                Map.of(table, uniqueConstraints));
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in a Postgres-driver
     * -specific compile-time dependency beyond the JDBC driver class itself (already an
     * {@code implementation} dependency of the generated app, per build.gradle.template). */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        private SingleConnectionUrlDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
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
