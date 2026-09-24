package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The file that ties an {@link ExportMain} run to the {@link ImportMain} run that reads it back: one
 * entry per exported table, carrying exactly the column facts {@link ImportStructureVerdict} needs to
 * compare against the TARGET's live schema at import time -- captured from the SOURCE at export time
 * via the same engine-agnostic {@code SqlDialect.listColumnsSql()} contract
 * {@link DataTransferIntrospection} reads live.
 *
 * <p>Written and read as a plain Jackson tree, not bound to a record type: this module's Jackson
 * configuration is not known to support constructor-parameter record binding, and a hand-built tree
 * needs no such support.
 */
final class DataTransferManifest {

    static final String SCHEMA_VERSION = "npdev-db-transfer-manifest.v1";
    static final String FILE_NAME = "manifest.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record ColumnMeta(String name, String dataType, boolean nullable, String columnDefault) {
    }

    record TableManifest(String table, long rowCount, List<ColumnMeta> columns) {
    }

    record Manifest(String format, String sourceUrl, List<TableManifest> tables) {
    }

    private DataTransferManifest() {
    }

    static void write(Path directory, Manifest manifest) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("format", manifest.format());
        root.put("sourceUrl", manifest.sourceUrl());
        root.put("exportedAt", Instant.now().toString());
        ArrayNode tables = root.putArray("tables");
        for (TableManifest table : manifest.tables()) {
            ObjectNode tableNode = tables.addObject();
            tableNode.put("table", table.table());
            tableNode.put("rowCount", table.rowCount());
            ArrayNode columns = tableNode.putArray("columns");
            for (ColumnMeta column : table.columns()) {
                ObjectNode columnNode = columns.addObject();
                columnNode.put("name", column.name());
                columnNode.put("dataType", column.dataType());
                columnNode.put("nullable", column.nullable());
                if (column.columnDefault() == null) {
                    columnNode.putNull("columnDefault");
                } else {
                    columnNode.put("columnDefault", column.columnDefault());
                }
            }
        }
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(FILE_NAME), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    static Manifest read(Path directory) throws IOException {
        JsonNode root = MAPPER.readTree(directory.resolve(FILE_NAME).toFile());
        String format = root.path("format").asText("csv");
        String sourceUrl = root.hasNonNull("sourceUrl") ? root.path("sourceUrl").asText() : null;
        List<TableManifest> tables = new ArrayList<>();
        for (JsonNode tableNode : root.path("tables")) {
            List<ColumnMeta> columns = new ArrayList<>();
            for (JsonNode columnNode : tableNode.path("columns")) {
                columns.add(new ColumnMeta(
                        columnNode.path("name").asText(),
                        columnNode.path("dataType").asText(),
                        columnNode.path("nullable").asBoolean(true),
                        columnNode.hasNonNull("columnDefault") ? columnNode.path("columnDefault").asText() : null));
            }
            tables.add(new TableManifest(tableNode.path("table").asText(), tableNode.path("rowCount").asLong(0),
                    List.copyOf(columns)));
        }
        return new Manifest(format, sourceUrl, List.copyOf(tables));
    }
}
