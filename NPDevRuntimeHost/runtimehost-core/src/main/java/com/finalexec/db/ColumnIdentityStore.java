package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialects;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

/**
 * REG-209 (B1 lift, {@code ALL_HITTABLE_LIFT_PLAN_2026-09-05.md} package P7): persists the model's
 * declared column {@code uid}s against the LIVE column each was last seen bound to, so a later boot
 * -- even one whose model.json was hand-edited to rename that column, with no {@code renamedFrom}
 * marker at all -- can recognize the rename by identity instead of needing it declared per rename.
 *
 * <p>Self-bootstrapped exactly like {@link SchemaHistoryStore}'s {@code npdev_schema_history} -- a
 * plain {@code CREATE TABLE IF NOT EXISTS} this class issues itself, reusing {@link
 * SqlDialects#guardedCreateTable} rather than inventing a second self-creation pattern.
 *
 * <p>Written by {@link SchemaLifecycleExecutor#afterMigrate} after EVERY successful pass -- including
 * one that changed nothing -- because the first post-uid boot has no baseline to resolve a FUTURE
 * rename against otherwise. Read by {@link SchemaLifecycleExecutor#attemptInPlaceRenames} (via {@link
 * #resolveRenamesByIdentity}), ahead of the model's own {@code businessTableRenamedColumns()}, so an
 * identity-matched rename is fed into the exact same rename machinery a declared {@code renamedFrom}
 * already uses -- no parallel rename path.
 */
final class ColumnIdentityStore {

    private static final String TABLE = "npdev_column_identity";

    private ColumnIdentityStore() {
    }

    /**
     * Writes the model's current {@code table -> column -> uid} map, replacing whatever this table
     * previously recorded for each (table, column) pair named in the map. A table/column no longer
     * present in {@code uids} (e.g. the model dropped that field's uid, or the column itself was
     * dropped) keeps its last-recorded row rather than being deleted -- harmless: {@link
     * #resolveRenamesByIdentity} only ever consults a row whose uid still matches something the
     * CURRENT model declares, so a stale row for a column that no longer exists live is simply never
     * matched again. Tolerates a write failure without throwing (same resilience posture as {@link
     * SchemaHistoryStore}'s own history-row writes) -- this is a convenience mechanism for a FUTURE
     * rename, never a correctness gate for the boot in progress.
     */
    static void record(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        Map<String, Map<String, String>> uids = manifest.businessTableColumnUids();
        if (uids.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long recordedAtUtc = System.currentTimeMillis();
                for (Map.Entry<String, Map<String, String>> tableEntry : uids.entrySet()) {
                    String table = tableEntry.getKey();
                    for (Map.Entry<String, String> columnEntry : tableEntry.getValue().entrySet()) {
                        upsert(connection, table, columnEntry.getKey(), columnEntry.getValue(), recordedAtUtc);
                    }
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            System.out.println("NPDev schema lifecycle: WARNING -- failed persisting the column identity map "
                    + "(a future rename may not be resolved by identity until this succeeds): " + failure.getMessage());
        }
    }

    /**
     * REG-209 step 4: augments {@code manifest.businessTableRenamedColumns()} with any rename this
     * table's persisted identity map explains that the model's OWN declared renames do not -- a live
     * column whose recorded uid matches a model field's declared uid, but whose name differs, IS a
     * declared rename (identity says so), fed into the returned map in the exact same {@code
     * newColumnName -> oldColumnName} shape {@code businessTableRenamedColumns()} already uses. A
     * model with no uids declared at all returns {@code manifest.businessTableRenamedColumns()}
     * completely unchanged -- behaves exactly as today, no persisted-table read even attempted.
     */
    static Map<String, Map<String, String>> resolveRenamesByIdentity(
            DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        Map<String, Map<String, String>> declaredUids = manifest.businessTableColumnUids();
        if (declaredUids.isEmpty()) {
            return manifest.businessTableRenamedColumns();
        }
        Map<String, Map<String, String>> persisted = load(dataSource);
        if (persisted.isEmpty()) {
            return manifest.businessTableRenamedColumns();
        }
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> tableEntry : manifest.businessTableRenamedColumns().entrySet()) {
            merged.put(tableEntry.getKey(), new LinkedHashMap<>(tableEntry.getValue()));
        }
        for (Map.Entry<String, Map<String, String>> tableEntry : declaredUids.entrySet()) {
            String table = tableEntry.getKey();
            Map<String, String> persistedForTable = persisted.getOrDefault(table, Map.of());
            if (persistedForTable.isEmpty()) {
                continue;
            }
            // Invert: uid -> the column it was last recorded against, live.
            Map<String, String> lastKnownColumnByUid = new LinkedHashMap<>();
            for (Map.Entry<String, String> persistedEntry : persistedForTable.entrySet()) {
                lastKnownColumnByUid.put(persistedEntry.getValue(), persistedEntry.getKey());
            }
            for (Map.Entry<String, String> modelEntry : tableEntry.getValue().entrySet()) {
                String modelColumn = modelEntry.getKey();
                String uid = modelEntry.getValue();
                String lastKnownColumn = lastKnownColumnByUid.get(uid);
                if (lastKnownColumn == null || lastKnownColumn.equalsIgnoreCase(modelColumn)) {
                    continue;
                }
                // The model's own declared renamedFrom, if present, wins -- an identity match is a
                // fallback for a hand-edit that carried no marker, never a second opinion overriding
                // an explicit declaration.
                merged.computeIfAbsent(table, t -> new LinkedHashMap<>())
                        .putIfAbsent(modelColumn, lastKnownColumn);
            }
        }
        return Map.copyOf(merged);
    }

    /** {@code table_name -> column_name -> uid}, every row this database has ever recorded. */
    private static Map<String, Map<String, String>> load(DataSource dataSource) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection)) {
                return Map.of();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT table_name, column_name, uid FROM " + TABLE);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String table = resultSet.getString("table_name");
                    String column = resultSet.getString("column_name");
                    String uid = resultSet.getString("uid");
                    if (table == null || column == null || uid == null) {
                        continue;
                    }
                    out.computeIfAbsent(table.toLowerCase(Locale.ROOT), t -> new LinkedHashMap<>())
                            .put(column.toLowerCase(Locale.ROOT), uid);
                }
            }
        } catch (SQLException failure) {
            return Map.of();
        }
        return out;
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, TABLE, null)) {
            if (tables.next()) {
                return true;
            }
        }
        // Some engines report unquoted identifiers upper-cased in DatabaseMetaData#getTables.
        try (ResultSet tables = connection.getMetaData().getTables(null, null, TABLE.toUpperCase(Locale.ROOT), null)) {
            return tables.next();
        }
    }

    private static void ensureTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().guardedCreateTable(TABLE,
                        "CREATE TABLE " + TABLE
                        + " (table_name " + InternalDdlTypes.text() + " NOT NULL, "
                        + "column_name " + InternalDdlTypes.text() + " NOT NULL, "
                        + "uid " + InternalDdlTypes.text() + " NOT NULL, "
                        + "recorded_at_utc BIGINT NOT NULL)")
        )) {
            statement.executeUpdate();
        }
    }

    private static void upsert(Connection connection, String table, String column, String uid, long recordedAtUtc)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE table_name = ? AND column_name = ?")) {
            delete.setString(1, table);
            delete.setString(2, column);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (table_name, column_name, uid, recorded_at_utc) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, table);
            insert.setString(2, column);
            insert.setString(3, uid);
            insert.setLong(4, recordedAtUtc);
            insert.executeUpdate();
        }
    }
}
