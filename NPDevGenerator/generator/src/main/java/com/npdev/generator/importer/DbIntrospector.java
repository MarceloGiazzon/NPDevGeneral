package com.npdev.generator.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * IMP-1: database-first introspection. Reads a live JDBC schema through {@link DatabaseMetaData} and
 * drafts a NPDev {@code model.json} plus an {@code import-report.json} saying what was mapped, what was
 * not and what needs a human. Nothing here is guessed: a column whose type is not in {@link #TYPE_MAPPING}
 * is left OUT of the model and listed; a composite or missing primary key gets a synthetic {@code id}
 * and a warning; defaults are reported, never imported; the legacy table/column name is kept in the
 * report and in each field's {@code description} so data and reports can still be mapped back.
 *
 * <p>The draft is a starting point for a person, validated by the platform's own {@code validateModel};
 * it is not claimed to match how the shop thinks about its entities.
 */
public final class DbIntrospector {

    /** The documented JDBC-type mapping, also written into the report so a reader can audit it. */
    public static final Map<String, String> TYPE_MAPPING = typeMapping();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------------------------------------------------------------- source model

    record Column(String name, int jdbcType, String typeName, int size, int scale, boolean nullable, String defaultValue) {
    }

    record ForeignKey(String name, String pkTable, List<String> columns, List<String> pkColumns) {
    }

    record Index(String name, boolean unique, List<String> columns) {
    }

    record Table(String name, List<Column> columns, List<String> primaryKey, List<ForeignKey> foreignKeys,
                 List<Index> uniqueIndexes) {
    }

    private DbIntrospector() {
    }

    private static Map<String, String> typeMapping() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("CHAR, VARCHAR, NCHAR, NVARCHAR, LONGVARCHAR", "string (maxLength = column size when it is a sane length)");
        map.put("CLOB, NCLOB", "string (no maxLength)");
        map.put("TINYINT, SMALLINT, INTEGER", "integer");
        map.put("BIGINT", "long");
        map.put("NUMERIC/DECIMAL, scale 0, precision <= 9", "integer");
        map.put("NUMERIC/DECIMAL, scale 0, precision <= 18", "long");
        map.put("NUMERIC/DECIMAL otherwise", "decimal (precision, scale)");
        map.put("REAL, FLOAT, DOUBLE", "decimal");
        map.put("BOOLEAN, BIT", "boolean");
        map.put("DATE", "date");
        map.put("TIMESTAMP, TIMESTAMP WITH TIME ZONE", "datetime");
        map.put("UUID (driver type name)", "uuid");
        map.put("ENUM('a', 'b', ...) (the driver type name lists the values)", "enum (enumValues = the listed values; a driver that reports a bare ENUM is NOT MAPPED)");
        map.put("TIME, BINARY, VARBINARY, BLOB, ARRAY, STRUCT, OTHER ...", "NOT MAPPED -- column omitted and listed in unmappedColumns");
        return map;
    }

    // ---------------------------------------------------------------- reading

    /** Reads every plain TABLE in {@code schema} (null = the connection's own schema). */
    static List<Table> read(Connection connection, String schema, List<String> skippedViews) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String effectiveSchema = schema != null && !schema.isBlank() ? schema : connection.getSchema();
        List<String> tableNames = new ArrayList<>();
        try (ResultSet rs = meta.getTables(connection.getCatalog(), effectiveSchema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if ("VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE"))) {
                    skippedViews.add(name);
                } else {
                    tableNames.add(name);
                }
            }
        }
        tableNames.sort(Comparator.naturalOrder());
        List<Table> tables = new ArrayList<>();
        for (String tableName : tableNames) {
            tables.add(readTable(meta, connection.getCatalog(), effectiveSchema, tableName));
        }
        return tables;
    }

    private static Table readTable(DatabaseMetaData meta, String catalog, String schema, String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                columns.add(new Column(
                        rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, rs.getString("COLUMN_DEF")));
            }
        }
        Map<Short, String> pk = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                pk.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        Map<String, Map<Short, String[]>> fks = new LinkedHashMap<>();
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                String key = rs.getString("FK_NAME") + "|" + rs.getString("PKTABLE_NAME");
                fks.computeIfAbsent(key, k -> new TreeMap<>())
                        .put(rs.getShort("KEY_SEQ"), new String[]{rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")});
            }
        }
        List<ForeignKey> foreignKeys = new ArrayList<>();
        fks.forEach((key, parts) -> {
            List<String> fkColumns = new ArrayList<>();
            List<String> pkColumns = new ArrayList<>();
            parts.values().forEach(pair -> {
                fkColumns.add(pair[0]);
                pkColumns.add(pair[1]);
            });
            int bar = key.indexOf('|');
            foreignKeys.add(new ForeignKey(key.substring(0, bar), key.substring(bar + 1), fkColumns, pkColumns));
        });
        Map<String, Map<Short, String>> indexes = new LinkedHashMap<>();
        Map<String, Boolean> indexUnique = new HashMap<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (indexName == null || column == null) {
                    continue;
                }
                indexUnique.put(indexName, !rs.getBoolean("NON_UNIQUE"));
                indexes.computeIfAbsent(indexName, k -> new TreeMap<>()).put(rs.getShort("ORDINAL_POSITION"), column);
            }
        }
        List<Index> uniqueIndexes = new ArrayList<>();
        Set<List<String>> pkColumns = Set.of(new ArrayList<>(pk.values()));
        indexes.forEach((name, cols) -> {
            List<String> list = new ArrayList<>(cols.values());
            if (indexUnique.getOrDefault(name, false) && !pkColumns.contains(list)) {
                uniqueIndexes.add(new Index(name, true, list));
            }
        });
        return new Table(table, columns, new ArrayList<>(pk.values()), foreignKeys, uniqueIndexes);
    }

    // ---------------------------------------------------------------- drafting

    /** The drafted model and its report. */
    public record Result(ObjectNode model, ObjectNode report) {
    }

    public static Result introspect(Connection connection, String schema, String namespace, String sourceLabel)
            throws SQLException {
        List<String> skippedViews = new ArrayList<>();
        List<Table> tables = read(connection, schema, skippedViews);
        return draft(tables, skippedViews, namespace, sourceLabel);
    }

    static Result draft(List<Table> tables, List<String> skippedViews, String namespace, String sourceLabel) {
        // Concept names first: references need the target's final name.
        Map<String, String> conceptByTable = new LinkedHashMap<>();
        Set<String> usedConcepts = new HashSet<>();
        for (Table table : tables) {
            conceptByTable.put(table.name(), unique(NameStyle.pascal(table.name()), usedConcepts));
        }
        Map<String, Table> tableByName = new HashMap<>();
        tables.forEach(t -> tableByName.put(t.name(), t));

        ObjectNode model = MAPPER.createObjectNode();
        model.put("dslVersion", "1.0.0");
        model.put("namespace", namespace);
        model.put("version", "0.1.0");
        ArrayNode concepts = model.putArray("concepts");

        ObjectNode report = MAPPER.createObjectNode();
        report.put("schemaVersion", "npdev-import-report.v1");
        report.put("source", sourceLabel);
        report.put("note", "Draft produced by database introspection. Validated on one source kind (JDBC metadata); "
                + "names and entity boundaries are a proposal for a person to review, not a claim about the business.");
        ArrayNode typeTable = report.putArray("typeMapping");
        TYPE_MAPPING.forEach((from, to) -> typeTable.addObject().put("from", from).put("to", to));
        ArrayNode tableReports = report.putArray("tables");
        ArrayNode unmapped = report.putArray("unmappedColumns");
        ArrayNode warnings = report.putArray("warnings");
        ArrayNode defaults = report.putArray("defaultsNotImported");
        ArrayNode manual = report.putArray("manualItems");
        ArrayNode linkProposals = report.putArray("linkTableProposals");

        int mappedColumns = 0;
        int totalColumns = 0;
        for (Table table : tables) {
            String concept = conceptByTable.get(table.name());
            ObjectNode conceptNode = concepts.addObject();
            conceptNode.put("name", concept);
            ArrayNode fields = conceptNode.putArray("fields");
            ObjectNode tableReport = tableReports.addObject();
            tableReport.put("table", table.name());
            tableReport.put("concept", concept);
            ObjectNode columnMap = tableReport.putObject("columns");

            Set<String> usedFields = new HashSet<>();
            Map<String, String> fieldByColumn = new LinkedHashMap<>();
            boolean singlePk = table.primaryKey().size() == 1;
            if (!singlePk) {
                // Never lose the key columns, never invent a business key: a synthetic id carries identity.
                String why = table.primaryKey().isEmpty() ? "has no primary key"
                        : "has the composite primary key (" + String.join(", ", table.primaryKey()) + ")";
                ObjectNode id = fields.addObject();
                id.put("name", unique("id", usedFields));
                id.put("type", "uuid");
                id.put("id", true);
                id.put("required", true);
                id.put("description", "Synthetic identity: source table " + table.name() + " " + why + ".");
                tableReport.put("idStrategy", "synthetic");
                if (table.primaryKey().isEmpty()) {
                    warnings.add(table.name() + " " + why + " -> a synthetic uuid id was added.");
                    manual.add(table.name() + ": decide identity (" + why + ")");
                } else {
                    warnings.add(table.name() + " " + why + " -> a synthetic uuid id was added and the source key is kept as a "
                            + "unique index (sourceKey).");
                    manual.add(table.name() + ": review the link table (" + why + "); it keeps a unique index on that key, "
                            + "consider an aggregate child or a many-to-many instead.");
                }
            } else {
                tableReport.put("idStrategy", "source-primary-key");
            }

            Map<String, ForeignKey> singleFkByColumn = new HashMap<>();
            for (ForeignKey fk : table.foreignKeys()) {
                if (fk.columns().size() == 1 && conceptByTable.containsKey(fk.pkTable())) {
                    singleFkByColumn.put(fk.columns().get(0), fk);
                } else {
                    manual.add(table.name() + ": foreign key " + fk.name() + " (" + String.join(", ", fk.columns()) + ") -> "
                            + fk.pkTable() + (fk.columns().size() > 1 ? " is composite" : " targets a table outside the import")
                            + "; its columns are plain fields, not a reference.");
                }
            }
            Set<String> uniqueColumns = new HashSet<>();
            List<Index> multiColumnIndexes = new ArrayList<>();
            for (Index index : table.uniqueIndexes()) {
                if (index.columns().size() == 1) {
                    uniqueColumns.add(index.columns().get(0));
                } else {
                    multiColumnIndexes.add(index);
                }
            }
            if (table.primaryKey().size() > 1 && multiColumnIndexes.stream()
                    .noneMatch(i -> new HashSet<>(i.columns()).equals(new HashSet<>(table.primaryKey())))) {
                // The synthetic id carries identity; the source key stays unique so the same pair cannot repeat.
                multiColumnIndexes.add(new Index("source_key", true, table.primaryKey()));
            }

            for (Column column : table.columns()) {
                totalColumns++;
                String mapped = mapType(column);
                if (mapped == null) {
                    ObjectNode u = unmapped.addObject();
                    u.put("table", table.name());
                    u.put("column", column.name());
                    u.put("typeName", column.typeName());
                    u.put("jdbcType", column.jdbcType());
                    u.put("severity", table.primaryKey().contains(column.name()) || !column.nullable() ? "required-column-lost" : "optional");
                    continue;
                }
                mappedColumns++;
                String fieldName = unique(NameStyle.camel(column.name()), usedFields);
                fieldByColumn.put(column.name(), fieldName);
                columnMap.put(column.name(), fieldName);
                ObjectNode field = fields.addObject();
                field.put("name", fieldName);
                boolean isId = singlePk && table.primaryKey().get(0).equals(column.name());
                ForeignKey fk = isId ? null : singleFkByColumn.get(column.name());
                if (fk != null) {
                    field.put("type", "reference");
                    field.putObject("reference").put("target", conceptByTable.get(fk.pkTable()));
                    Table target = tableByName.get(fk.pkTable());
                    if (target != null && !(target.primaryKey().size() == 1 && target.primaryKey().get(0).equals(fk.pkColumns().get(0)))) {
                        warnings.add(table.name() + "." + column.name() + " references " + fk.pkTable() + "." + fk.pkColumns().get(0)
                                + ", which is not that table's primary key; the draft reference points at the concept's id.");
                    }
                } else {
                    applyType(field, mapped, column);
                }
                if (isId) {
                    field.put("id", true);
                    field.put("required", true);
                } else if (!column.nullable()) {
                    field.put("required", true);
                }
                if (uniqueColumns.contains(column.name())) {
                    field.put("unique", true);
                }
                field.put("description", "Imported from column " + table.name() + "." + column.name() + " (" + column.typeName() + ")");
                if (column.defaultValue() != null) {
                    ObjectNode d = defaults.addObject();
                    d.put("table", table.name());
                    d.put("column", column.name());
                    d.put("default", column.defaultValue());
                }
            }
            if (!multiColumnIndexes.isEmpty()) {
                ArrayNode indexNodes = conceptNode.putArray("indexes");
                for (Index index : multiColumnIndexes) {
                    List<String> mappedFields = new ArrayList<>();
                    boolean complete = true;
                    for (String c : index.columns()) {
                        String f = fieldByColumn.get(c);
                        if (f == null) {
                            complete = false;
                        } else {
                            mappedFields.add(f);
                        }
                    }
                    if (complete) {
                        ObjectNode node = indexNodes.addObject();
                        node.put("name", NameStyle.camel(index.name()));
                        ArrayNode indexFields = node.putArray("fields");
                        mappedFields.forEach(indexFields::add);
                        node.put("unique", true);
                    } else {
                        manual.add(table.name() + ": unique index " + index.name() + " uses an unmapped column; not imported.");
                    }
                }
                if (indexNodes.isEmpty()) {
                    conceptNode.remove("indexes");
                }
            }
            proposeForLinkTable(table, concept, conceptByTable, singleFkByColumn, fieldByColumn, linkProposals);
        }
        for (String view : skippedViews) {
            manual.add("view " + view + " skipped (views are not concepts; recreate it as a query if needed)");
        }
        ObjectNode counts = report.putObject("counts");
        counts.put("tables", tables.size());
        counts.put("viewsSkipped", skippedViews.size());
        counts.put("columns", totalColumns);
        counts.put("columnsMapped", mappedColumns);
        counts.put("columnsUnmapped", totalColumns - mappedColumns);
        counts.put("manualItems", manual.size());
        return new Result(model, report);
    }

    // ---------------------------------------------------------------- types

    /** The DSL type for a column, or null when the type is not in the documented mapping. */
    static String mapType(Column column) {
        if ("UUID".equalsIgnoreCase(column.typeName())) {
            return "uuid";
        }
        if (enumValues(column) != null) {
            return "enum";
        }
        switch (column.jdbcType()) {
            case Types.CHAR: case Types.VARCHAR: case Types.NCHAR: case Types.NVARCHAR: case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR: case Types.CLOB: case Types.NCLOB:
                return "string";
            case Types.TINYINT: case Types.SMALLINT: case Types.INTEGER:
                return "integer";
            case Types.BIGINT:
                return "long";
            case Types.NUMERIC: case Types.DECIMAL:
                if (column.scale() == 0 && column.size() > 0 && column.size() <= 9) {
                    return "integer";
                }
                if (column.scale() == 0 && column.size() > 0 && column.size() <= 18) {
                    return "long";
                }
                return "decimal";
            case Types.REAL: case Types.FLOAT: case Types.DOUBLE:
                return "decimal";
            case Types.BOOLEAN: case Types.BIT:
                return "boolean";
            case Types.DATE:
                return "date";
            case Types.TIMESTAMP: case Types.TIMESTAMP_WITH_TIMEZONE:
                return "datetime";
            default:
                return null;
        }
    }

    /**
     * The values of a native ENUM column, parsed from a type name such as {@code ENUM('G', 'PG-13')}; null when the
     * type is not an ENUM, the driver reports a bare {@code ENUM} (values unknown), or the list would not pass the
     * DSL's enum rules (empty, blank or duplicate value).
     */
    static List<String> enumValues(Column column) {
        String typeName = column.typeName();
        if (typeName == null) {
            return null;
        }
        String text = typeName.trim();
        if (text.length() < 6 || !text.regionMatches(true, 0, "ENUM", 0, 4)) {
            return null;
        }
        int i = 4;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length() || text.charAt(i) != '(') {
            return null;
        }
        i++;
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (true) {
            while (i < text.length() && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
                i++;
            }
            if (i >= text.length()) {
                return null;
            }
            if (text.charAt(i) == ')') {
                break;
            }
            if (text.charAt(i) != '\'') {
                return null;
            }
            i++;
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        value.append('\'');
                        i += 2;
                        continue;
                    }
                    closed = true;
                    i++;
                    break;
                }
                value.append(c);
                i++;
            }
            if (!closed || value.toString().isBlank() || !seen.add(value.toString().trim().toLowerCase(Locale.ROOT))) {
                return null;
            }
            values.add(value.toString());
        }
        return values.isEmpty() ? null : values;
    }

    private static void applyType(ObjectNode field, String type, Column column) {
        field.put("type", type);
        if ("enum".equals(type)) {
            ArrayNode values = field.putArray("enumValues");
            enumValues(column).forEach(values::add);
        }
        if ("string".equals(type) && column.jdbcType() != Types.CLOB && column.jdbcType() != Types.NCLOB
                && column.size() > 0 && column.size() <= 100_000) {
            field.put("maxLength", column.size());
        }
        if ("decimal".equals(type) && (column.jdbcType() == Types.NUMERIC || column.jdbcType() == Types.DECIMAL)) {
            if (column.size() > 0) {
                field.put("precision", column.size());
            }
            field.put("scale", Math.max(0, column.scale()));
        }
    }

    /**
     * A table whose composite primary key is made of foreign keys to two imported tables is a link table.
     * The report only PROPOSES what to do with it (never applied): a pure link (key columns only) stays its own
     * concept; a link that carries its own data is an association, or an aggregate child of whichever side is
     * the parent -- the aggregate snippet uses only constructs the DSL has (aggregates[].collections).
     */
    /** Audit columns (who/when a row was touched) do not make a link table "carry its own data". */
    private static boolean isAuditColumn(String column) {
        return AUDIT_COLUMNS.contains(column.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
    }

    private static final Set<String> AUDIT_COLUMNS = Set.of(
            "createdat", "createdon", "createddate", "createdtime", "createdts", "createdby", "createdate", "creationdate",
            "updatedat", "updatedon", "updateddate", "updatedtime", "updatedts", "updatedby", "updatedate",
            "modifiedat", "modifiedon", "modifieddate", "modifiedby", "lastupdate", "lastupdated", "lastmodified",
            "lastmodifiedat", "lastmodifiedby", "lastupdatedat", "lastupdatedby");

    private static void proposeForLinkTable(Table table, String concept, Map<String, String> conceptByTable,
                                            Map<String, ForeignKey> singleFkByColumn, Map<String, String> fieldByColumn,
                                            ArrayNode proposals) {
        if (table.primaryKey().size() < 2) {
            return;
        }
        List<String> keyFkColumns = new ArrayList<>();
        for (String column : table.primaryKey()) {
            if (singleFkByColumn.containsKey(column) && fieldByColumn.containsKey(column)) {
                keyFkColumns.add(column);
            }
        }
        if (keyFkColumns.size() != 2 || table.primaryKey().size() != 2) {
            return;
        }
        Set<String> keyColumns = new HashSet<>(table.primaryKey());
        List<String> candidates = table.columns().stream().map(Column::name)
                .filter(c -> !keyColumns.contains(c) && fieldByColumn.containsKey(c)).toList();
        List<String> extra = candidates.stream().filter(c -> !isAuditColumn(c)).map(fieldByColumn::get).toList();
        List<String> audit = candidates.stream().filter(DbIntrospector::isAuditColumn).map(fieldByColumn::get).toList();
        String first = conceptByTable.get(singleFkByColumn.get(keyFkColumns.get(0)).pkTable());
        String second = conceptByTable.get(singleFkByColumn.get(keyFkColumns.get(1)).pkTable());
        ObjectNode proposal = proposals.addObject();
        proposal.put("table", table.name());
        proposal.put("concept", concept);
        proposal.putArray("between").add(first).add(second);
        ArrayNode extraNode = proposal.putArray("extraFields");
        extra.forEach(extraNode::add);
        if (!audit.isEmpty()) {
            ArrayNode auditNode = proposal.putArray("auditFieldsIgnored");
            audit.forEach(auditNode::add);
        }
        if (extra.isEmpty()) {
            proposal.put("kind", "pureLink");
            proposal.put("suggestion", "Pure link between " + first + " and " + second + ": keep " + concept
                    + " as its own concept; the sourceKey unique index already stops the same pair twice.");
            return;
        }
        proposal.put("kind", "associationWithData");
        proposal.put("suggestion", concept + " links " + first + " and " + second + " and carries its own data (" + String.join(", ", extra)
                + "): keep it as an association concept, or model it as an aggregate child of whichever side is the parent "
                + "(see aggregateOptions). Which side owns it is a business decision, so nothing is applied.");
        ArrayNode options = proposal.putArray("aggregateOptions");
        for (int i = 0; i < 2; i++) {
            String parent = i == 0 ? first : second;
            String childField = fieldByColumn.get(keyFkColumns.get(i));
            ObjectNode option = options.addObject();
            option.put("parent", parent);
            ObjectNode snippet = option.putObject("aggregate");
            snippet.put("name", parent + "Aggregate");
            snippet.put("root", parent);
            ObjectNode collection = snippet.putArray("collections").addObject();
            collection.put("name", NameStyle.camel(table.name()));
            collection.put("concept", concept);
            collection.put("childField", childField);
            collection.put("ownership", "owned");
        }
    }

    private static String unique(String base, Set<String> used) {
        String candidate = base.isEmpty() ? "x" : base;
        int n = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + n++;
        }
        return candidate;
    }

    /** Legacy-name policy: readable PascalCase / camelCase; the original always stays in the report. */
    static final class NameStyle {
        private NameStyle() {
        }

        static String pascal(String legacy) {
            String camel = camel(legacy);
            return camel.isEmpty() ? "Table" : Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        }

        static String camel(String legacy) {
            String[] parts = legacy.trim().split("[^A-Za-z0-9]+");
            boolean shouting = legacy.equals(legacy.toUpperCase(Locale.ROOT));
            StringBuilder out = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                String word = shouting ? part.toLowerCase(Locale.ROOT) : part;
                if (out.length() == 0) {
                    out.append(Character.toLowerCase(word.charAt(0))).append(word.substring(1));
                } else {
                    out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
            }
            String result = out.toString();
            if (!result.isEmpty() && Character.isDigit(result.charAt(0))) {
                result = "f" + result;
            }
            return result;
        }
    }
}
