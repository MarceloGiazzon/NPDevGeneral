package com.finalexec.db;

import com.finalexec.boundary.BoundaryBootException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 9 B3 (docs/ACCEPTED_BOUNDARIES.md B9): proves {@link SchemaDropSnapshotRestorer} against a
 * REAL {@link SchemaDropSnapshotWriter} snapshot (not a hand-authored fixture) -- write a table's
 * rows for real, snapshot it via the actual production writer, recreate/mutate the live table the
 * way a real destructive recreation would, then restore and verify against the real database.
 */
class SchemaDropSnapshotRestorerTest {

    private static final String TABLE = "widgets_" + Math.abs(new java.util.Random().nextInt());

    private DataSource dataSource;
    private Path snapshotDir;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException, IOException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        if (snapshotDir != null && Files.isDirectory(snapshotDir)) {
            try (var stream = Files.walk(snapshotDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("preview reports every snapshot row as an insert when the live table is empty (post-recreate)")
    void previewReportsRowsToInsertAfterRecreate() throws SQLException {
        createLiveTable();
        UUID a = insertRow("Alpha", "Open");
        UUID b = insertRow("Beta", "Closed");
        snapshotNow();
        recreateEmptyLiveTable();

        SchemaDropSnapshotRestorer.Preview preview = SchemaDropSnapshotRestorer.preview(dataSource, snapshotDir.getFileName().toString(), TABLE);

        assertEquals(2, preview.rowsInSnapshot());
        assertEquals(2, preview.rowsToInsert());
        assertEquals(0, preview.rowsAlreadyPresentIdentical());
        assertTrue(preview.conflictingIds().isEmpty());
    }

    @Test
    @DisplayName("apply actually re-inserts the missing rows into the live (recreated) table")
    void applyInsertsMissingRows() throws SQLException {
        createLiveTable();
        insertRow("Alpha", "Open");
        insertRow("Beta", "Closed");
        snapshotNow();
        recreateEmptyLiveTable();

        SchemaDropSnapshotRestorer.RestoreResult result =
                SchemaDropSnapshotRestorer.apply(dataSource, snapshotDir.getFileName().toString(), TABLE);

        assertEquals(2, result.rowsInserted());
        assertEquals(0, result.rowsAlreadyPresentIdentical());
        assertTrue(result.conflictingIds().isEmpty());
        assertEquals(2, countLiveRows());
    }

    @Test
    @DisplayName("apply skips a row that is already live with identical content -- no duplicate insert")
    void applySkipsIdenticalRowsAlreadyLive() throws SQLException {
        createLiveTable();
        insertRow("Alpha", "Open");
        snapshotNow();
        // Table was NOT recreated -- the row is still there, identical to the snapshot.

        SchemaDropSnapshotRestorer.RestoreResult result =
                SchemaDropSnapshotRestorer.apply(dataSource, snapshotDir.getFileName().toString(), TABLE);

        assertEquals(0, result.rowsInserted());
        assertEquals(1, result.rowsAlreadyPresentIdentical());
        assertTrue(result.conflictingIds().isEmpty());
        assertEquals(1, countLiveRows());
    }

    @Test
    @DisplayName("a row present live with DIFFERENT content is reported as a conflict and never overwritten")
    void conflictingRowsAreReportedAndNeverOverwritten() throws SQLException {
        createLiveTable();
        UUID id = insertRow("Alpha", "Open");
        snapshotNow();
        // Something wrote to this row since the snapshot -- live now disagrees with the snapshot.
        updateRow(id, "Alpha-Renamed", "Open");

        SchemaDropSnapshotRestorer.Preview preview = SchemaDropSnapshotRestorer.preview(dataSource, snapshotDir.getFileName().toString(), TABLE);
        assertEquals(1, preview.conflictingIds().size());
        assertEquals(0, preview.rowsToInsert());

        SchemaDropSnapshotRestorer.RestoreResult result =
                SchemaDropSnapshotRestorer.apply(dataSource, snapshotDir.getFileName().toString(), TABLE);
        assertEquals(1, result.conflictingIds().size());
        assertEquals(0, result.rowsInserted());
        assertEquals("Alpha-Renamed", readName(id), "a conflicting row must never be overwritten by restore");
    }

    @Test
    @DisplayName("apply refuses when the target table does not exist live -- restore is data-only, never schema")
    void applyRefusesWhenLiveTableIsMissing() throws SQLException {
        createLiveTable();
        insertRow("Alpha", "Open");
        snapshotNow();
        dropLiveTableEntirely();

        BoundaryBootException exception = assertThrows(BoundaryBootException.class,
                () -> SchemaDropSnapshotRestorer.apply(dataSource, snapshotDir.getFileName().toString(), TABLE));
        assertTrue(exception.getMessage().contains("does not exist"), exception.getMessage());
        assertTrue(exception.getMessage().contains("boot the app normally"), exception.getMessage());
        // B9 (docs/ACCEPTED_BOUNDARIES.md, 2026-08-25 W2.3): the violation carries both the
        // boundaryId (already wired) and the declared code (previously absent, now a message prefix).
        assertEquals("B9", exception.getViolation().boundaryId());
        assertTrue(exception.getMessage().contains("B9:single_table_scope:"), exception.getMessage());
    }

    @Test
    @DisplayName("listSnapshots and tablesInSnapshot reflect exactly what the writer produced")
    void listingReflectsWhatWasWritten() throws SQLException {
        createLiveTable();
        insertRow("Alpha", "Open");
        snapshotNow();

        assertTrue(SchemaDropSnapshotRestorer.listSnapshots().contains(snapshotDir.getFileName().toString()));
        assertTrue(SchemaDropSnapshotRestorer.tablesInSnapshot(snapshotDir.getFileName().toString())
                .contains(TABLE.toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    @DisplayName("STOR-18: missingLiveTables reports exactly the tables that do not exist live, checking the WHOLE set in one pass")
    void missingLiveTablesReportsExactlyTheTablesThatDoNotExistLive() throws SQLException {
        String otherTable = TABLE + "_missing";
        createLiveTable();
        insertRow("Alpha", "Open");
        snapshotNow();
        // otherTable was never created live at all -- the batch endpoint's preflight (STOR-18) must
        // name it as missing without needing a snapshot file for it.

        List<String> missing = SchemaDropSnapshotRestorer.missingLiveTables(dataSource, List.of(TABLE, otherTable));

        assertEquals(List.of(otherTable), missing);
    }

    @Test
    @DisplayName("STOR-18: a conflict restoring one table does not affect restoring an independent second table in the same batch")
    void aConflictInOneTableDoesNotBlockRestoringAnotherTableInTheSameBatch() throws SQLException {
        String tableB = TABLE + "_b";
        createLiveTable();
        createLiveTable(tableB);
        UUID conflicting = insertRow("Alpha", "Open");
        insertRow(tableB, "Widget", "New");
        Set<String> before = Set.copyOf(SchemaDropSnapshotRestorer.listSnapshots());
        SchemaDropSnapshotWriter.snapshotBeforeDrop(dataSource, List.of(TABLE, tableB));
        String snapshotName = SchemaDropSnapshotRestorer.listSnapshots().stream()
                .filter(name -> !before.contains(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("snapshotBeforeDrop did not produce a new snapshot directory"));
        snapshotDir = java.nio.file.Paths.get("runtime-data", "schema-snapshot-before-drop", snapshotName);
        // Something wrote to TABLE's row since the snapshot; tableB's row is untouched (already
        // identical). Neither table was recreated -- this exercises the "already-live" branches of
        // apply(), which is what a real batch call walks through per table.
        updateRow(conflicting, "Alpha-Renamed", "Open");

        // The controller loops apply() once per table in FK-plan order, accumulating every result
        // regardless of an earlier table's outcome -- reproduced directly here at the service layer,
        // the same two calls SchemaAcknowledgmentController#restoreSnapshotBatch makes.
        SchemaDropSnapshotRestorer.RestoreResult resultA = SchemaDropSnapshotRestorer.apply(dataSource, snapshotName, TABLE);
        SchemaDropSnapshotRestorer.RestoreResult resultB = SchemaDropSnapshotRestorer.apply(dataSource, snapshotName, tableB);

        assertEquals(1, resultA.conflictingIds().size(), "the conflicting row must still be reported");
        assertEquals(0, resultA.rowsInserted());
        assertEquals(0, resultB.conflictingIds().size(), "tableB's independent row must be unaffected by TABLE's conflict");
        assertEquals(1, resultB.rowsAlreadyPresentIdentical());
        assertEquals("Alpha-Renamed", readName(conflicting), "the conflicting row is still never overwritten");
    }

    @Test
    @DisplayName("a path-traversal attempt in the snapshot or table name is rejected outright")
    void pathTraversalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchemaDropSnapshotRestorer.preview(dataSource, "../../etc", TABLE));
        assertThrows(IllegalArgumentException.class, () -> SchemaDropSnapshotRestorer.preview(dataSource, "20260101-000000-000", "../../etc"));
    }

    private void createLiveTable() throws SQLException {
        createLiveTable(TABLE);
    }

    private void createLiveTable(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id UUID PRIMARY KEY, name VARCHAR(50), status VARCHAR(20))");
        }
    }

    private void recreateEmptyLiveTable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + TABLE);
            statement.execute("CREATE TABLE " + TABLE + " (id UUID PRIMARY KEY, name VARCHAR(50), status VARCHAR(20))");
        }
    }

    private void dropLiveTableEntirely() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + TABLE);
        }
    }

    private UUID insertRow(String name, String status) throws SQLException {
        return insertRow(TABLE, name, status);
    }

    private UUID insertRow(String table, String name, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + table + " (id, name, status) VALUES (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setString(3, status);
            statement.executeUpdate();
        }
        return id;
    }

    private void updateRow(UUID id, String name, String status) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + TABLE + " SET name = ?, status = ? WHERE id = ?")) {
            statement.setString(1, name);
            statement.setString(2, status);
            statement.setObject(3, id);
            statement.executeUpdate();
        }
    }

    private String readName(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT name FROM " + TABLE + " WHERE id = ?")) {
            statement.setObject(1, id);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private int countLiveRows() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    /** Invokes the REAL production writer (same package) so the fixture this test restores from is
     * byte-for-byte what a genuine destructive recreation would have produced -- not a hand-authored
     * approximation of its format. */
    private void snapshotNow() {
        Set<String> before = Set.copyOf(SchemaDropSnapshotRestorer.listSnapshots());
        SchemaDropSnapshotWriter.snapshotBeforeDrop(dataSource, List.of(TABLE));
        List<String> after = SchemaDropSnapshotRestorer.listSnapshots();
        String newest = after.stream().filter(name -> !before.contains(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("snapshotBeforeDrop did not produce a new snapshot directory"));
        snapshotDir = java.nio.file.Paths.get("runtime-data", "schema-snapshot-before-drop", newest);
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
