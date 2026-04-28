package com.finalexec.npdev.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class MigrationSharedSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MigrationSharedSupport() {
    }

    static Map<String, StorageTableSchema> indexTables(StorageSchemaSnapshot snapshot) {
        Map<String, StorageTableSchema> out = new LinkedHashMap<>();
        for (StorageTableSchema table : snapshot.normalized().tables()) {
            out.put(table.name(), table);
        }
        return out;
    }

    static Map<String, StorageColumnSchema> indexColumns(StorageTableSchema table) {
        Map<String, StorageColumnSchema> out = new LinkedHashMap<>();
        for (StorageColumnSchema column : table.normalized().columns()) {
            out.put(column.name(), column);
        }
        return out;
    }

    static List<MigrationOperation> diff(StorageSchemaSnapshot previous, StorageSchemaSnapshot current) {
        StorageSchemaSnapshot prev = previous == null ? new StorageSchemaSnapshot("none", List.of()) : previous.normalized();
        StorageSchemaSnapshot curr = current == null ? new StorageSchemaSnapshot("unknown", List.of()) : current.normalized();

        Map<String, StorageTableSchema> prevTables = indexTables(prev);
        Map<String, StorageTableSchema> currTables = indexTables(curr);
        List<MigrationOperation> operations = new ArrayList<>();

        for (StorageTableSchema currTable : curr.tables()) {
            StorageTableSchema prevTable = prevTables.get(currTable.name());
            if (prevTable == null) {
                operations.add(new MigrationOperation("CREATE_TABLE", currTable.name(), null, "new table"));
                for (StorageColumnSchema column : currTable.columns()) {
                    operations.add(new MigrationOperation("ADD_COLUMN", currTable.name(), column.name(), column.sqlType()));
                }
                continue;
            }

            Map<String, StorageColumnSchema> prevColumns = indexColumns(prevTable);
            for (StorageColumnSchema currColumn : currTable.columns()) {
                StorageColumnSchema prevColumn = prevColumns.get(currColumn.name());
                if (prevColumn == null) {
                    operations.add(new MigrationOperation("ADD_COLUMN", currTable.name(), currColumn.name(), currColumn.sqlType()));
                    continue;
                }
                if (!Objects.equals(prevColumn.sqlType(), currColumn.sqlType())) {
                    operations.add(new MigrationOperation("ALTER_COLUMN_TYPE", currTable.name(), currColumn.name(), prevColumn.sqlType() + " -> " + currColumn.sqlType()));
                }
                if (!prevColumn.required() && currColumn.required()) {
                    operations.add(new MigrationOperation("SET_NOT_NULL", currTable.name(), currColumn.name(), "required=true"));
                }
            }

            Map<String, StorageColumnSchema> currColumns = indexColumns(currTable);
            for (StorageColumnSchema prevColumn : prevTable.columns()) {
                if (!currColumns.containsKey(prevColumn.name())) {
                    operations.add(new MigrationOperation("DROP_COLUMN", prevTable.name(), prevColumn.name(), "removed"));
                }
            }
        }

        for (StorageTableSchema prevTable : prev.tables()) {
            if (!currTables.containsKey(prevTable.name())) {
                operations.add(new MigrationOperation("DROP_TABLE", prevTable.name(), null, "removed"));
            }
        }

        return operations.stream()
                .sorted(Comparator
                        .comparing(MigrationOperation::kind)
                        .thenComparing(item -> item.tableName() == null ? "" : item.tableName())
                        .thenComparing(item -> item.columnName() == null ? "" : item.columnName()))
                .toList();
    }

    static String hash(StorageSchemaSnapshot snapshot) {
        try {
            String canonical = MAPPER.writeValueAsString(snapshot == null ? new StorageSchemaSnapshot("none", List.of()).normalized() : snapshot.normalized());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : bytes) {
                builder.append(String.format(Locale.ROOT, "%02x", current));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash schema snapshot", exception);
        }
    }

    static JsonNode toJson(Object value) {
        return MAPPER.valueToTree(value);
    }
}
