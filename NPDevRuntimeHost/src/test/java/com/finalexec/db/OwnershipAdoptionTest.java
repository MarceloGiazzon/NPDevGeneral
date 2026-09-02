package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SchemaDiffEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B8 (Wave 2 package 2.1, docs/ACCEPTED_BOUNDARIES.md): coverage for the second half of the B8 lift
 * -- explicit, operator-driven adoption of ownership for a table that predates ownership recording.
 * Against a real H2 database, same {@code SingleConnectionUrlDataSource} shape every sibling db test
 * file in this package uses.
 */
class OwnershipAdoptionTest {

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

    @Test
    void previewAndApplyAdoptALegacyTableThatMatchesTheManifestExactly() throws SQLException {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));
        // Synthesized straight FROM the manifest (MissingTableCreationPass -- the same production path
        // forcePhysicalSchema/Ephemeral apps use to realize a schema with no real V1__ migration), so
        // it matches by construction -- never a hand-crafted table merely assumed to match.
        MissingTableCreationPass.createMissingBusinessTables(dataSource, manifest);
        assertTrue(new SchemaDiffEngine()
                        .diff(DesiredSchemaFactory.fromManifest(manifest), new CurrentSchemaReader().read(dataSource))
                        .isEmpty(),
                "precondition: the synthesized table must match the manifest exactly, or this test "
                        + "proves nothing about adoption's exact-match requirement");

        OwnershipAdoption.Preview preview = OwnershipAdoption.preview(dataSource, manifest);
        assertEquals(List.of("widgets"),
                preview.adoptable().stream().map(OwnershipAdoption.Candidate::table).toList());
        assertTrue(preview.notAdoptable().isEmpty());
        assertTrue(preview.alreadyOwned().isEmpty());

        OwnershipAdoption.Result result = OwnershipAdoption.apply(dataSource, manifest);
        assertEquals(List.of("widgets"), result.adopted());
        assertTrue(result.rejected().isEmpty());

        Set<String> owned = SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource);
        assertNotNull(owned);
        assertTrue(owned.contains("widgets"));
    }

    @Test
    void aHandCreatedTableWithADifferentShapeIsNeverAdoptedAndNeverSweptIntoTheDestructivePath() throws SQLException {
        // A hand-made table with the SAME NAME the manifest declares, but a shape NPDev never
        // created (missing a declared column) -- exactly the case adoption must never touch.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));

        OwnershipAdoption.Preview preview = OwnershipAdoption.preview(dataSource, manifest);
        assertTrue(preview.adoptable().isEmpty(), "a shape mismatch must never be reported adoptable");
        assertEquals(1, preview.notAdoptable().size());
        assertEquals("widgets", preview.notAdoptable().get(0).table());
        assertFalse(preview.notAdoptable().get(0).mismatchReasons().isEmpty());

        OwnershipAdoption.Result result = OwnershipAdoption.apply(dataSource, manifest);
        assertTrue(result.adopted().isEmpty(),
                "apply must never adopt a table whose shape does not match the manifest exactly");

        Set<String> owned = SchemaLifecycleExecutor.readOwnedBusinessTables(dataSource);
        assertTrue(owned == null || owned.isEmpty(), "the hand-created table must never be recorded as owned");

        // The B8 guarantee this whole mechanism protects: with no ownership recorded, a boot that no
        // longer declares 'widgets' must still refuse to touch it.
        SchemaLifecycleExecutor.SchemaManifest withoutWidgets = manifest(Map.of(), Map.of());
        try (Connection connection = dataSource.getConnection()) {
            Set<String> dropped = SchemaLifecycleExecutor.droppedConceptTables(
                    connection.getMetaData(), dataSource, withoutWidgets);
            assertEquals(Set.of(), dropped,
                    "a hand-created table must never be swept into the destructive drop path, even "
                            + "after an adoption preview/apply ran against it and correctly rejected it");
        }
    }

    @Test
    void alreadyOwnedTableIsExcludedFromAdoptionCandidatesEntirely() throws SQLException {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));
        MissingTableCreationPass.createMissingBusinessTables(dataSource, manifest);
        SchemaLifecycleExecutor.recordOwnershipForLiveManifestTables(dataSource, manifest);

        OwnershipAdoption.Preview preview = OwnershipAdoption.preview(dataSource, manifest);
        assertEquals(List.of("widgets"), preview.alreadyOwned());
        assertTrue(preview.adoptable().isEmpty());
        assertTrue(preview.notAdoptable().isEmpty());
    }

    @Test
    void aDeclaredTableThatWasNeverCreatedIsNotAnAdoptionCandidate() {
        // Declared in the model but genuinely never realized -- nothing live to adopt, and it must
        // not be reported under EITHER bucket (adoptable or notAdoptable would both be misleading).
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", Map.of("id", "BIGINT", "name", "VARCHAR(50)")));

        OwnershipAdoption.Preview preview = OwnershipAdoption.preview(dataSource, manifest);
        assertTrue(preview.adoptable().isEmpty());
        assertTrue(preview.notAdoptable().isEmpty());
        assertTrue(preview.alreadyOwned().isEmpty());
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(),
                List.copyOf(businessTableColumns.keySet()), businessTableColumns,
                Map.of(), businessTableColumnTypes, Map.of(), Map.of(), true,
                "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "",
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; same shape every sibling db test file uses. */
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
