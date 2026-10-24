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
 * REG-305: the table-level twin of {@link ColumnIdentityStore}. Persists each model concept's declared
 * {@code uid} against the table it was last seen bound to, so a later boot whose model renamed that
 * concept with no {@code renamedFrom} marker recognizes the table rename by identity -- what
 * model.schema.json has always promised for {@code concept.uid}, and what nothing implemented before.
 *
 * <p>Self-bootstrapped exactly like {@link ColumnIdentityStore}'s {@code npdev_column_identity}. Written
 * by {@link AfterMigratePass} after every successful pass; read by {@link
 * SchemaLifecycleExecutor#attemptInPlaceTableRenames} (via {@link #resolveRenamesByIdentity}), whose
 * result feeds the same {@code businessTableRenames} map a declared concept {@code renamedFrom} uses --
 * no parallel rename path.
 */
final class TableIdentityStore {

    private static final String TABLE = "npdev_table_identity";

    private TableIdentityStore() {
    }

    /**
     * Writes the model's current {@code table -> uid} map: one row per uid, so a uid that moved to a new
     * table name replaces its old row. Tolerates a write failure (same posture as {@link
     * ColumnIdentityStore#record}) -- a convenience for a FUTURE rename, never a gate on this boot.
     */
    static void record(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        Map<String, String> uids = manifest.businessTableUids();
        if (uids.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureTable(connection);
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long recordedAtUtc = System.currentTimeMillis();
                for (Map.Entry<String, String> entry : uids.entrySet()) {
                    upsert(connection, entry.getKey(), entry.getValue(), recordedAtUtc);
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failure) {
            System.out.println("NPDev schema lifecycle: WARNING -- failed persisting the table identity map "
                    + "(a future concept rename may not be resolved by identity until this succeeds): " + failure.getMessage());
        }
    }

    /**
     * {@code manifest.businessTableRenames()} ({@code newTable -> oldTable}) augmented with every rename
     * the persisted identity map explains: a model table whose declared uid was last recorded against a
     * DIFFERENT table name. The model's own declared {@code renamedFrom} wins. Whether the old table still
     * exists live (and the new one does not) is left to the canonical diff, exactly as for a declared
     * rename, so a stale row can never rename anything. No uids declared -> the declared map unchanged.
     */
    static Map<String, String> resolveRenamesByIdentity(
            DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        Map<String, String> declaredUids = manifest.businessTableUids();
        if (declaredUids.isEmpty()) {
            return manifest.businessTableRenames();
        }
        Map<String, String> lastKnownTableByUid = load(dataSource);
        if (lastKnownTableByUid.isEmpty()) {
            return manifest.businessTableRenames();
        }
        Map<String, String> merged = new LinkedHashMap<>(manifest.businessTableRenames());
        for (Map.Entry<String, String> modelEntry : declaredUids.entrySet()) {
            String modelTable = modelEntry.getKey();
            String lastKnownTable = lastKnownTableByUid.get(modelEntry.getValue());
            if (lastKnownTable == null || lastKnownTable.equalsIgnoreCase(modelTable)) {
                continue;
            }
            merged.putIfAbsent(modelTable, lastKnownTable);
        }
        return Map.copyOf(merged);
    }

    /** {@code uid -> table_name}, every row this database has recorded. */
    private static Map<String, String> load(DataSource dataSource) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection)) {
                return Map.of();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT table_name, uid FROM " + TABLE);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String table = resultSet.getString("table_name");
                    String uid = resultSet.getString("uid");
                    if (table != null && uid != null) {
                        out.put(uid, table.toLowerCase(Locale.ROOT));
                    }
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
                        + "uid " + InternalDdlTypes.text() + " NOT NULL, "
                        + "recorded_at_utc BIGINT NOT NULL)")
        )) {
            statement.executeUpdate();
        }
    }

    private static void upsert(Connection connection, String table, String uid, long recordedAtUtc)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE uid = ? OR table_name = ?")) {
            delete.setString(1, uid);
            delete.setString(2, table);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (table_name, uid, recorded_at_utc) VALUES (?, ?, ?)")) {
            insert.setString(1, table);
            insert.setString(2, uid);
            insert.setLong(3, recordedAtUtc);
            insert.executeUpdate();
        }
    }
}
