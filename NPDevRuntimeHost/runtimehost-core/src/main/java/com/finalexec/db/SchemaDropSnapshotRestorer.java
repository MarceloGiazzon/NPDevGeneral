package com.finalexec.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.boundary.*;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Move 9 B3 (docs/ACCEPTED_BOUNDARIES.md B9): an operator-driven restore for a
 * {@link SchemaDropSnapshotWriter} snapshot -- the write side has existed since REG-8; there was no
 * restore path anywhere, only "restore by hand from the plain JSONL" advice. This never runs
 * automatically, never restores "everything": every call names an explicit snapshot + table, and
 * {@link #apply} is the ONLY method that writes (dry-run via {@link #preview} is the default way to
 * use this).
 *
 * <p>Restores DATA only, never schema: the target table must already exist live (recreated by a
 * normal boot first) -- this command does not issue DDL. A row already present in the live table
 * with IDENTICAL content is skipped silently; a row present with DIFFERENT content (something wrote
 * to that id since the drop) is reported as a conflict and never overwritten, by design -- resolving
 * a real conflict is a judgement call this tool deliberately does not make for the operator.
 */
public final class SchemaDropSnapshotRestorer {

    private static final Path SNAPSHOT_BASE = Paths.get("runtime-data", "schema-snapshot-before-drop");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SchemaDropSnapshotRestorer() {
    }

    public record Preview(String snapshot, String table, int rowsInSnapshot, int rowsToInsert,
                   int rowsAlreadyPresentIdentical, List<String> conflictingIds) {
    }

    public record RestoreResult(String snapshot, String table, int rowsInSnapshot, int rowsInserted,
                          int rowsAlreadyPresentIdentical, List<String> conflictingIds) {
    }

    /** Every snapshot directory available to restore from, most recent first. */
    public static List<String> listSnapshots() {
        if (!Files.isDirectory(SNAPSHOT_BASE)) {
            return List.of();
        }
        try (var stream = Files.list(SNAPSHOT_BASE)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed listing snapshots in " + SNAPSHOT_BASE.toAbsolutePath(), exception);
        }
    }

    /** Every table this ONE snapshot captured (i.e. every {@code *.jsonl} file it wrote). */
    public static List<String> tablesInSnapshot(String snapshot) {
        Path dir = snapshotDir(snapshot);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.jsonl$", ""))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed listing tables in snapshot " + snapshot, exception);
        }
    }

    /** Read-only: compares the snapshot's rows against the live table's CURRENT rows (matched by
     * {@code id}). Writes nothing -- the default, sanctioned way to use this class. */
    public static Preview preview(DataSource dataSource, String snapshot, String table) {
        List<Map<String, Object>> snapshotRows = readSnapshotRows(snapshot, table);
        int toInsert = 0;
        int alreadyPresent = 0;
        List<String> conflicts = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (Map<String, Object> row : snapshotRows) {
                Optional<Map<String, Object>> live = readLiveRow(connection, table, idValue(row));
                if (live.isEmpty()) {
                    toInsert++;
                } else if (rowsMatch(live.get(), row)) {
                    alreadyPresent++;
                } else {
                    conflicts.add(String.valueOf(idValue(row)));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed comparing snapshot rows against the live table " + table, exception);
        }
        return new Preview(snapshot, table, snapshotRows.size(), toInsert, alreadyPresent, conflicts);
    }

    /**
     * Applies the restore: INSERTs every snapshot row not already present in the live table.
     * Skips (never touches) a row that already matches live; NEVER overwrites a conflicting row
     * (same id, different content) -- those are reported in the result, exactly as {@link #preview}
     * reported them, and are left for the operator to resolve by hand.
     *
     * @throws BoundaryBootException if the target table does not exist live -- restore is DATA-only,
     *                                never schema; boot the app normally first so the table exists.
     */
    public static RestoreResult apply(DataSource dataSource, String snapshot, String table) {
        List<Map<String, Object>> snapshotRows = readSnapshotRows(snapshot, table);
        int inserted = 0;
        int alreadyPresent = 0;
        List<String> conflicts = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExistsLive(connection, table)) {
                // B9 (snapshot restore): bulk restore refused -- target table must exist first.
                throw new BoundaryBootException(new BoundaryViolation("B9", "restore",
                        "Snapshot restore refused: table '" + table + "' does not exist in the live database; "
                                + "boot the app normally first so its schema is created, then restore.",
                        Instant.now()));
            }
            for (Map<String, Object> row : snapshotRows) {
                Optional<Map<String, Object>> live = readLiveRow(connection, table, idValue(row));
                if (live.isPresent()) {
                    if (rowsMatch(live.get(), row)) {
                        alreadyPresent++;
                    } else {
                        conflicts.add(String.valueOf(idValue(row)));
                    }
                    continue;
                }
                insertRow(connection, table, row);
                inserted++;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed restoring snapshot rows into " + table, exception);
        }
        return new RestoreResult(snapshot, table, snapshotRows.size(), inserted, alreadyPresent, conflicts);
    }

    private static Path snapshotDir(String snapshot) {
        String safe = requireSafePathSegment(snapshot, "snapshot");
        return SNAPSHOT_BASE.resolve(safe);
    }

    private static Path snapshotFile(String snapshot, String table) {
        String safeTable = requireSafePathSegment(table, "table");
        return snapshotDir(snapshot).resolve(safeTable.toLowerCase(Locale.ROOT) + ".jsonl");
    }

    /** An operator-supplied snapshot/table name must never escape {@link #SNAPSHOT_BASE} -- rejects
     * blank values and any path-traversal attempt outright rather than trying to sanitize one. */
    private static String requireSafePathSegment(String value, String fieldName) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\")) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + value);
        }
        return trimmed;
    }

    private static List<Map<String, Object>> readSnapshotRows(String snapshot, String table) {
        Path file = snapshotFile(snapshot, table);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No snapshot file found for table '" + table + "' in snapshot '"
                    + snapshot + "' (expected " + file.toAbsolutePath() + ")");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                rows.add(OBJECT_MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {
                }));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed reading snapshot file " + file, exception);
        }
        return rows;
    }

    private static boolean tableExistsLive(Connection connection, String table) throws SQLException {
        return !SchemaLifecycleExecutor.readActualColumns(connection.getMetaData(), table).isEmpty();
    }

    private static Optional<Map<String, Object>> readLiveRow(Connection connection, String table, Object id) throws SQLException {
        if (id == null || !tableExistsLive(connection, table)) {
            return Optional.empty();
        }
        String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM " + safeTable + " WHERE " + SchemaLifecycleExecutor.quotedIdentifier("id") + " = ?")) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                ResultSetMetaData metadata = resultSet.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    row.put(metadata.getColumnLabel(i), resultSet.getObject(i));
                }
                return Optional.of(row);
            }
        }
    }

    /** Compares by STRING form of every value the snapshot recorded (the snapshot itself already
     * flattened binary/JSON columns into JSON-safe forms -- see SchemaDropSnapshotWriter) -- close
     * enough to detect a real conflict without engine-specific type-equality edge cases. */
    private static boolean rowsMatch(Map<String, Object> live, Map<String, Object> snapshotRow) {
        for (Map.Entry<String, Object> entry : snapshotRow.entrySet()) {
            Object liveValue = findIgnoreCase(live, entry.getKey());
            if (!Objects.equals(normalizeForCompare(liveValue), normalizeForCompare(entry.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeForCompare(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** {@code row.get("id")} directly would silently return {@code null} for every row on an engine
     * (H2, by default) that reports unquoted-declared columns back in UPPERCASE -- the snapshot's
     * JSON key is genuinely {@code "ID"}, not {@code "id"}. A silently-null id makes {@link
     * #readLiveRow} short-circuit to "not live" for every row, which would have restore blindly
     * re-INSERT rows that are already there (a real bug this lookup exists to prevent). */
    private static Object idValue(Map<String, Object> row) {
        return findIgnoreCase(row, "id");
    }

    private static Object findIgnoreCase(Map<String, Object> map, String key) {
        Object direct = map.get(key);
        if (direct != null || map.containsKey(key)) {
            return direct;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Inserts only the columns BOTH the snapshot row and the live table's CURRENT shape agree on --
     * the table's shape may have evolved since the drop (a column since removed, or a new required
     * column since added -- the latter would need its own default/backfill, out of scope for a pure
     * data restore, and is left for the operator to notice and handle). */
    private static void insertRow(Connection connection, String table, Map<String, Object> row) throws SQLException {
        Set<String> liveColumns = SchemaLifecycleExecutor.readActualColumns(connection.getMetaData(), table);
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String matched = matchLiveColumn(liveColumns, entry.getKey());
            if (matched != null) {
                columns.add(matched);
                values.add(entry.getValue());
            }
        }
        if (columns.isEmpty()) {
            return;
        }
        String safeTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        String columnList = columns.stream().map(SchemaLifecycleExecutor::safeIdentifier).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + safeTable + " (" + columnList + ") VALUES (" + placeholders + ")")) {
            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }
            statement.executeUpdate();
        }
    }

    private static String matchLiveColumn(Set<String> liveColumns, String snapshotColumn) {
        for (String candidate : liveColumns) {
            if (candidate.equalsIgnoreCase(snapshotColumn)) {
                return candidate;
            }
        }
        return null;
    }
}
