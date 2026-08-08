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
import com.npdev.kernel.storage.sql.SqlDialects;
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

    /**
     * Best-effort full dump as JSON-lines. A genuine BLOB/binary column's value is replaced with a
     * note, not crashed on or pulled into the dump raw. JSON-typed columns (object/array DSL
     * fields) are NOT treated as binary even though the JDBC driver hands them back as byte[]/String
     * just like a real BLOB would -- without the type-name check below, a column the JDBC driver
     * happens to return as byte[] (H2 does this for JSON columns, not just BLOBs) would have its
     * actual content silently replaced by a "<binary, not snapshotted>" placeholder, defeating the
     * entire purpose of this snapshot for exactly the structured data it's most important to
     * capture (confirmed live: a Project.shipping object field was lost this way before this fix).
     */
    private static int dumpRows(Connection connection, Path snapshotDir, String table) throws SQLException, IOException {
        Path dumpFile = snapshotDir.resolve(table.toLowerCase(Locale.ROOT) + ".jsonl");
        int rowsWritten = 0;
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table);
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            boolean[] isJsonColumn = new boolean[columnCount + 1];
            for (int index = 1; index <= columnCount; index++) {
                isJsonColumn[index] = isJsonColumnType(metaData, index);
            }
            StringBuilder out = new StringBuilder();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 1; index <= columnCount; index++) {
                    String column = metaData.getColumnLabel(index);
                    row.put(column, snapshotColumnValue(resultSet, index, isJsonColumn[index]));
                }
                out.append(OBJECT_MAPPER.writeValueAsString(row)).append('\n');
                rowsWritten++;
            }
            Files.writeString(dumpFile, out.toString(), StandardCharsets.UTF_8);
        }
        return rowsWritten;
    }

    private static boolean isJsonColumnType(ResultSetMetaData metaData, int index) {
        try {
            // The engine's own JSON type names, not two spellings repeated at a third call site.
            return SqlDialects.active().isJsonColumnType(metaData.getColumnTypeName(index));
        } catch (SQLException exception) {
            return false;
        }
    }

    private static Object snapshotColumnValue(ResultSet resultSet, int index, boolean isJsonColumn) {
        try {
            Object value = resultSet.getObject(index);
            if (value == null) {
                return null;
            }
            if (isJsonColumn) {
                return decodeJsonColumnValue(value);
            }
            if (value instanceof byte[] bytes) {
                return "<binary, " + bytes.length + " bytes, not snapshotted>";
            }
            return value;
        } catch (SQLException exception) {
            return "<unreadable column: " + exception.getMessage() + ">";
        }
    }

    /**
     * A JSON-typed column's value, regardless of whether the JDBC driver handed it back as a
     * String or as byte[] (H2 does the latter): decode to text, then parse so the snapshot captures
     * real structured JSON, not an escaped string-within-a-string. One parse pass isn't always
     * enough -- confirmed live, a column written through Hibernate's JsonNode/JSON-column mapping
     * (the path the JPA entity save uses, separate from the snapshot's own raw JDBC read) round-trips
     * through one extra layer of JSON-string quoting that only Hibernate's own FormatMapper normally
     * reverses on read; a raw, Hibernate-unaware JDBC read like this one sees that extra layer
     * directly: the literal bytes are a JSON STRING LITERAL (starting with a quote, e.g.
     * {@code "{\"carrier\":...}"}), not bare object/array text. Keep parsing while the result is a
     * String that still looks like JSON -- object, array, OR a quoted string -- bounded so a value
     * that legitimately is just a plain string (no further nesting) terminates after unwrapping it.
     *
     * <p>Package-private (Move 9 A4): {@code CrossEngineDataPromotion} reuses this exact decode logic
     * when reading a JSONB-typed column from the source engine during an H2-&gt;Postgres data
     * promotion -- one JSON-decode dialect, not a second copy of this reasoning.
     */
    static Object decodeJsonColumnValue(Object value) {
        String json = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
        if (json.isBlank()) {
            return null;
        }
        Object decoded = json;
        for (int pass = 0; pass < 3; pass++) {
            if (!(decoded instanceof String text) || text.isBlank()) {
                break;
            }
            String trimmed = text.trim();
            boolean looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"");
            if (!looksLikeJson) {
                break;
            }
            try {
                decoded = OBJECT_MAPPER.readValue(trimmed, Object.class);
            } catch (Exception exception) {
                break;
            }
        }
        return decoded;
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
