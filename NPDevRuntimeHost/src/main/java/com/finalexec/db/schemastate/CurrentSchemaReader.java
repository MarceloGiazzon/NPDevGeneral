package com.finalexec.db.schemastate;

import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the live database's complete shape into a {@link CurrentSchema} — the "current" half of the
 * canonical desired-vs-current model (schema-engine rebuild, Phase 1). Consolidates the ~12 ad-hoc
 * {@code DatabaseMetaData} reads scattered across {@code SchemaLifecycleExecutor}'s passes (REG-6) into
 * one read-once, portable (H2 + PostgreSQL) reader. Every table/column/constraint name is lower-cased
 * (JDBC catalogs disagree on case across engines — H2 upper, Postgres lower); SQL types run through the
 * same {@link SqlTypeNormalization} the executor uses, so H2 and Postgres spellings compare equal.
 *
 * <p>Wired NOWHERE into the boot path in Phase 1 — behavior-preserving until Phase 4.
 */
public final class CurrentSchemaReader {

    /** System schemas whose tables are never part of an app's model (mirrors SchemaLifecycleExecutor). */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog");

    public CurrentSchema read(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();

            List<TableRef> tableRefs = readTableRefs(metadata, catalog);
            Map<String, CurrentTable> tables = new LinkedHashMap<>();
            for (TableRef ref : tableRefs) {
                Map<String, CurrentColumn> columns = readColumns(metadata, catalog, ref);
                List<String> primaryKey = readPrimaryKey(metadata, catalog, ref);
                List<CurrentIndex> indexes = readIndexes(metadata, catalog, ref);
                List<CurrentUniqueConstraint> uniques = deriveUniques(indexes, primaryKey);
                List<CurrentForeignKey> foreignKeys = readForeignKeys(metadata, catalog, ref);
                tables.put(ref.lowerName(), new CurrentTable(
                        ref.lowerName(), columns, primaryKey, uniques, foreignKeys, indexes));
            }
            return new CurrentSchema(Map.copyOf(tables));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed reading current database schema", exception);
        }
    }

    private static List<TableRef> readTableRefs(DatabaseMetaData metadata, String catalog) throws SQLException {
        List<TableRef> refs = new ArrayList<>();
        try (ResultSet rs = metadata.getTables(catalog, null, null, new String[] {"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                refs.add(new TableRef(schema, rs.getString("TABLE_NAME")));
            }
        }
        return refs;
    }

    private static Map<String, CurrentColumn> readColumns(DatabaseMetaData metadata, String catalog, TableRef ref)
            throws SQLException {
        Map<String, CurrentColumn> columns = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getColumns(catalog, ref.schema(), ref.rawName(), null)) {
            while (rs.next()) {
                String column = lower(rs.getString("COLUMN_NAME"));
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                Integer sizeOrNull = rs.wasNull() ? null : size;
                int scale = rs.getInt("DECIMAL_DIGITS");
                Integer scaleOrNull = rs.wasNull() ? null : scale;
                int nullableCode = rs.getInt("NULLABLE");
                boolean nullable = nullableCode != DatabaseMetaData.columnNoNulls;
                String columnDefault = rs.getString("COLUMN_DEF");
                columns.put(column, new CurrentColumn(
                        column,
                        SqlTypeNormalization.normalize(qualifyTypeWithSize(typeName, size, scale)),
                        sizeOrNull,
                        scaleOrNull,
                        nullable,
                        columnDefault == null ? null : columnDefault.trim()));
            }
        }
        return columns;
    }

    private static List<String> readPrimaryKey(DatabaseMetaData metadata, String catalog, TableRef ref)
            throws SQLException {
        // KEY_SEQ orders the columns within the key; collect then order by it.
        List<int[]> seqIndex = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (ResultSet rs = metadata.getPrimaryKeys(catalog, ref.schema(), ref.rawName())) {
            while (rs.next()) {
                seqIndex.add(new int[] {rs.getInt("KEY_SEQ"), names.size()});
                names.add(lower(rs.getString("COLUMN_NAME")));
            }
        }
        seqIndex.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<String> ordered = new ArrayList<>(seqIndex.size());
        for (int[] pair : seqIndex) {
            ordered.add(names.get(pair[1]));
        }
        return List.copyOf(ordered);
    }

    private static List<CurrentIndex> readIndexes(DatabaseMetaData metadata, String catalog, TableRef ref)
            throws SQLException {
        // Group rows by index name; each row is one column at ORDINAL_POSITION.
        Map<String, IndexAcc> byName = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getIndexInfo(catalog, ref.schema(), ref.rawName(), false, false)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue; // statistics row, not an index
                }
                String indexName = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (indexName == null || column == null) {
                    continue;
                }
                boolean unique = !rs.getBoolean("NON_UNIQUE");
                IndexAcc acc = byName.computeIfAbsent(lower(indexName), key -> new IndexAcc(unique));
                acc.columnsBySeq.add(new Object[] {rs.getInt("ORDINAL_POSITION"), lower(column)});
            }
        } catch (UnsupportedOperationException ignored) {
            // some drivers reject getIndexInfo on a view/temp; treat as no indexes
        }
        List<CurrentIndex> indexes = new ArrayList<>();
        for (Map.Entry<String, IndexAcc> entry : byName.entrySet()) {
            List<Object[]> cols = entry.getValue().columnsBySeq;
            cols.sort((a, b) -> Integer.compare((int) a[0], (int) b[0]));
            List<String> columnNames = new ArrayList<>(cols.size());
            for (Object[] c : cols) {
                columnNames.add((String) c[1]);
            }
            indexes.add(new CurrentIndex(entry.getKey(), List.copyOf(columnNames), entry.getValue().unique));
        }
        return List.copyOf(indexes);
    }

    /**
     * A unique constraint is backed by a unique index in both H2 and Postgres. Derive uniques from the
     * unique indexes, excluding the one that backs the primary key (same column set) so a PK is not
     * double-reported as a unique constraint.
     */
    private static List<CurrentUniqueConstraint> deriveUniques(List<CurrentIndex> indexes, List<String> primaryKey) {
        List<CurrentUniqueConstraint> uniques = new ArrayList<>();
        for (CurrentIndex index : indexes) {
            if (index.unique() && !index.columns().equals(primaryKey)) {
                uniques.add(new CurrentUniqueConstraint(index.name(), index.columns()));
            }
        }
        return List.copyOf(uniques);
    }

    private static List<CurrentForeignKey> readForeignKeys(DatabaseMetaData metadata, String catalog, TableRef ref)
            throws SQLException {
        Map<String, FkAcc> byName = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getImportedKeys(catalog, ref.schema(), ref.rawName())) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                String fkColumn = lower(rs.getString("FKCOLUMN_NAME"));
                String pkTable = lower(rs.getString("PKTABLE_NAME"));
                String pkColumn = lower(rs.getString("PKCOLUMN_NAME"));
                int keySeq = rs.getInt("KEY_SEQ");
                String onDelete = deleteRule(rs.getInt("DELETE_RULE"));
                String key = fkName == null ? "fk_" + fkColumn : lower(fkName);
                FkAcc acc = byName.computeIfAbsent(key, k -> new FkAcc(k, pkTable, onDelete));
                acc.rows.add(new Object[] {keySeq, fkColumn, pkColumn});
            }
        }
        List<CurrentForeignKey> fks = new ArrayList<>();
        for (FkAcc acc : byName.values()) {
            acc.rows.sort((a, b) -> Integer.compare((int) a[0], (int) b[0]));
            List<String> fkColumns = new ArrayList<>();
            List<String> refColumns = new ArrayList<>();
            for (Object[] row : acc.rows) {
                fkColumns.add((String) row[1]);
                refColumns.add((String) row[2]);
            }
            fks.add(new CurrentForeignKey(acc.name, List.copyOf(fkColumns), acc.referencedTable,
                    List.copyOf(refColumns), acc.onDelete));
        }
        return List.copyOf(fks);
    }

    private static String deleteRule(int code) {
        return switch (code) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case DatabaseMetaData.importedKeyNoAction -> "NO ACTION";
            default -> null;
        };
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /**
     * Reconstruct the parameterized SQL type from JDBC's bare {@code TYPE_NAME} + size/scale, mirroring
     * {@code SchemaLifecycleExecutor.qualifyTypeWithSize} EXACTLY so the shadow's current-side type
     * compares equal to the manifest's declared type (e.g. live {@code VARCHAR}+50 → {@code VARCHAR(50)}
     * == desired {@code VARCHAR(50)}). Kept byte-identical to the live formatter; if that one changes,
     * change this too (both feed the same {@code SqlTypeNormalization}).
     */
    private static String qualifyTypeWithSize(String typeName, int columnSize, int decimalDigits) {
        if (typeName == null || typeName.isBlank()) {
            return typeName;
        }
        String upper = typeName.toUpperCase(Locale.ROOT);
        if (upper.contains("CHAR")) {
            return typeName + "(" + columnSize + ")";
        }
        if (upper.equals("NUMERIC") || upper.equals("DECIMAL")) {
            return typeName + "(" + columnSize + "," + decimalDigits + ")";
        }
        return typeName;
    }

    private record TableRef(String schema, String rawName) {
        String lowerName() {
            return rawName == null ? null : rawName.toLowerCase(Locale.ROOT);
        }
    }

    private static final class IndexAcc {
        private final boolean unique;
        private final List<Object[]> columnsBySeq = new ArrayList<>();

        private IndexAcc(boolean unique) {
            this.unique = unique;
        }
    }

    private static final class FkAcc {
        private final String name;
        private final String referencedTable;
        private final String onDelete;
        private final List<Object[]> rows = new ArrayList<>();

        private FkAcc(String name, String referencedTable, String onDelete) {
            this.name = name;
            this.referencedTable = referencedTable;
            this.onDelete = onDelete;
        }
    }
}
