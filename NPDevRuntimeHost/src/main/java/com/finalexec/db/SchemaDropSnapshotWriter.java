package com.finalexec.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A destructive schema recreation (DROP TABLE) is the one operation in the schema lifecycle that
 * is genuinely, unavoidably lossy -- no engineering makes a narrowing/incompatible structural
 * change lossless. What this writer guarantees instead: every drop is preceded by a best-effort,
 * recoverable record of what was about to be deleted (row count, always; a full JSON-lines dump,
 * best-effort), so data loss is traceable and recoverable-from-snapshot rather than silent.
 *
 * <p>A snapshot failure (unreadable table, disk full, odd column type) is logged loudly but must
 * never block the boot/migration it's protecting -- a safety net that can itself bring the app
 * down would be worse than no safety net.</p>
 */
final class SchemaDropSnapshotWriter {
    private static final Path SNAPSHOT_BASE = Paths.get("runtime-data", "schema-snapshot-before-drop");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int MAX_RETAINED_SNAPSHOTS = 5;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SchemaDropSnapshotWriter() {
    }

    /**
     * Snapshots every listed table that actually exists, then prunes old snapshot directories
     * beyond the retention bound. Never throws -- any failure is caught and logged so the caller's
     * destructive recreation always proceeds.
     */
    static void snapshotBeforeDrop(DataSource dataSource, List<String> tables) {
        if (dataSource == null || tables == null || tables.isEmpty()) {
            return;
        }
        Path snapshotDir = SNAPSHOT_BASE.resolve(TIMESTAMP_FORMAT.format(Instant.now().atZone(java.time.ZoneOffset.UTC)));
        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException exception) {
            System.out.println("NPDev schema lifecycle: DATA LOSS NOT SNAPSHOTTED -- failed creating snapshot directory "
                    + snapshotDir + ": " + exception.getMessage());
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String table : tables) {
            if (table == null || table.isBlank()) {
                continue;
            }
            summary.put(table, snapshotTable(dataSource, snapshotDir, table));
        }
        try {
            Files.writeString(
                    snapshotDir.resolve("_summary.json"),
                    OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                    StandardCharsets.UTF_8
            );
            System.out.println("NPDev schema lifecycle: pre-drop snapshot written to " + snapshotDir.toAbsolutePath());
        } catch (IOException exception) {
            System.out.println("NPDev schema lifecycle: DATA LOSS NOT SNAPSHOTTED -- failed writing snapshot summary in "
                    + snapshotDir + ": " + exception.getMessage());
        }
        pruneOldSnapshots();
    }

    private static Map<String, Object> snapshotTable(DataSource dataSource, Path snapshotDir, String table) {
        Map<String, Object> tableSummary = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            long rowCount = readRowCount(connection, table);
            tableSummary.put("rowCount", rowCount);
            if (rowCount <= 0) {
                tableSummary.put("dumped", false);
                return tableSummary;
            }
            int dumped = dumpRows(connection, snapshotDir, table);
            tableSummary.put("dumped", true);
            tableSummary.put("dumpedRowCount", dumped);
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: DATA LOSS NOT SNAPSHOTTED for table \"" + table
                    + "\" -- " + exception.getMessage());
            tableSummary.put("error", String.valueOf(exception.getMessage()));
        }
        return tableSummary;
    }

    private static long readRowCount(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    /** Best-effort full dump as JSON-lines; a BLOB/binary column's value is replaced with a note, not crashed on. */
    private static int dumpRows(Connection connection, Path snapshotDir, String table) throws SQLException, IOException {
        Path dumpFile = snapshotDir.resolve(table.toLowerCase(Locale.ROOT) + ".jsonl");
        int rowsWritten = 0;
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table);
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            StringBuilder out = new StringBuilder();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 1; index <= columnCount; index++) {
                    String column = metaData.getColumnLabel(index);
                    row.put(column, snapshotColumnValue(resultSet, index));
                }
                out.append(OBJECT_MAPPER.writeValueAsString(row)).append('\n');
                rowsWritten++;
            }
            Files.writeString(dumpFile, out.toString(), StandardCharsets.UTF_8);
        }
        return rowsWritten;
    }

    private static Object snapshotColumnValue(ResultSet resultSet, int index) {
        try {
            Object value = resultSet.getObject(index);
            if (value instanceof byte[] bytes) {
                return "<binary, " + bytes.length + " bytes, not snapshotted>";
            }
            return value;
        } catch (SQLException exception) {
            return "<unreadable column: " + exception.getMessage() + ">";
        }
    }

    private static void pruneOldSnapshots() {
        try {
            if (!Files.isDirectory(SNAPSHOT_BASE)) {
                return;
            }
            List<Path> snapshots = new ArrayList<>();
            try (var stream = Files.list(SNAPSHOT_BASE)) {
                stream.filter(Files::isDirectory).forEach(snapshots::add);
            }
            snapshots.sort(Comparator.comparing(Path::getFileName).reversed());
            for (int index = MAX_RETAINED_SNAPSHOTS; index < snapshots.size(); index++) {
                deleteRecursively(snapshots.get(index));
            }
        } catch (IOException exception) {
            System.out.println("NPDev schema lifecycle: failed pruning old pre-drop snapshots: " + exception.getMessage());
        }
    }

    private static void deleteRecursively(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException | UncheckedIOException exception) {
            System.out.println("NPDev schema lifecycle: failed deleting old snapshot directory " + directory + ": " + exception.getMessage());
        }
    }
}
