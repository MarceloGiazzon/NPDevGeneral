package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.ManifestDrivenJavaSourcePluginHandler;
import com.finalexec.npdev.service.pluginipc.PluginIpcHostSession;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.storage.sql.PaginationClause;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B1 (REAL_LIFT_PLAN_2026-09-03, B13): runs one {@code javaHook} conversion's batches through the
 * isolated plugin pool -- the adapter between the conversion-hook vocabulary and the SEC-3/5/7
 * pool/admission machinery {@code plugin:java-source} already proved. Package-private, invoked only
 * from {@link ConversionHookRunner#run}, which owns hook selection/ordering/the closing {@code
 * verifySql} (unchanged, reused after this method returns) -- this class only runs the batch loop.
 *
 * <h2>Batch selection needs no explicit cursor</h2>
 * Each batch is "the next {@code batchSize} rows where any claimed column is still NULL" -- a
 * successfully written row drops out of that WHERE clause on the very next read, so repeatedly
 * re-running the SAME query IS the pagination: no {@code OFFSET}/keyset cursor to get wrong, and a
 * boot that crashes mid-loop resumes correctly for free (the next boot's first read simply sees
 * whatever is still NULL).
 *
 * <h2>Phase-journal participation (Done-when #4)</h2>
 * {@code ConversionHookRunner#run}'s own javadoc explains why a hook needs {@link
 * MigrationPhaseJournal#hasAnyActivity} even after its own claim reclassifies (a committed
 * {@code ADD COLUMN} changes the diff item {@code SchemaDiffEngine} reports) -- the SAME risk applies
 * here, since a javaHook's claims are ALSO {@code ADD_REQUIRED_COLUMN} entries. Each batch records a
 * {@link MigrationPhaseJournal.PhaseKey} (keyed by an ordinal local to this call, not stable across
 * boots -- resumption safety comes from the WHERE-NULL selection above, not from ordinal continuity)
 * hashed over the batch's row ids, written and committed TOGETHER with the batch's own writes on one
 * connection -- the identical atomicity argument {@link ConversionHookPhaseRunner}'s DML phase uses:
 * a crash before commit leaves nothing, a crash after leaves both durable.
 *
 * <h2>The child never gets a DataSource (Done-when #2)</h2>
 * The child's method return value carries the write back to the host (via {@link
 * ManifestDrivenJavaSourcePluginHandler}, reused verbatim -- no new child-side class); THIS class
 * performs the actual {@code UPDATE} on the host connection the journal row shares. The child issues
 * no callback and is given none to use.
 */
final class JavaMigrationHookRunner {

    private static final String BATCH_SIZE_PROPERTY = "npdev.schema.conversionHooks.javaHookBatchSize";
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final String ADAPTER_ID = "plugin:java-migration-hook";

    private JavaMigrationHookRunner() {
    }

    static void run(DataSource dataSource, String migrationId, ConversionHookRunner.Hook hook,
            ConversionHookRunner.JavaHookRuntimeContext context) {
        run(dataSource, migrationId, hook, context, Integer.MAX_VALUE);
    }

    /** Test-only seam (mirrors A1's own {@code MigrationKillMidPhaseHarness} trick): running only a
     *  KNOWN prefix of batches, then having the caller {@code Runtime.halt()}, is equivalent to a real
     *  crash for proving "the next boot resumes correctly" -- real crash timing is nondeterministic
     *  anyway, so a deterministic prefix proves the identical property. Production code always calls
     *  the 4-arg overload above, which passes {@link Integer#MAX_VALUE}. */
    static void run(DataSource dataSource, String migrationId, ConversionHookRunner.Hook hook,
            ConversionHookRunner.JavaHookRuntimeContext context, int maxBatches) {
        if (context == null || context.pool() == null) {
            throw new IllegalStateException("Conversion hook '" + hook.id() + "' declares a javaHook but no "
                    + "PluginIpcChildProcessPool is available -- this should never happen for a properly "
                    + "generated app (ConversionHookJavaHookEmitter always registers a "
                    + "java-source-runtime-refs.json entry alongside a javaHook hook.json, which is what "
                    + "makes that pool bean non-null; NpdevPluginConfig).");
        }

        String table = parseTable(hook);
        List<String> claimedColumns = parseColumns(hook);
        String primaryKeyColumn = primaryKeyColumn(dataSource, table, hook);
        int batchSize = resolveBatchSize();

        int batchOrdinal = 0;
        while (batchOrdinal < maxBatches) {
            List<Map<String, Object>> batch;
            try (Connection connection = dataSource.getConnection()) {
                batch = readNextBatch(connection, table, primaryKeyColumn, claimedColumns, batchSize);
            } catch (SQLException exception) {
                throw new IllegalStateException("Conversion hook '" + hook.id()
                        + "' failed reading its next row batch: " + exception.getMessage(), exception);
            }
            if (batch.isEmpty()) {
                return;
            }

            List<Map<String, Object>> writes = invokeChild(context, hook, batch);
            if (writes.isEmpty()) {
                // No batch that read rows may write nothing back: the WHERE-NULL selection above
                // would just re-select the identical rows forever. A hook that genuinely cannot
                // resolve every row must still name each id it touches -- leaving a claimed column
                // NULL on a row it wrote is a legitimate way to fail the closing verifySql (same X0
                // discipline every other op's generated SQL already carries), but writing NOTHING at
                // all for a non-empty batch is a stuck hook, refused here rather than spun forever.
                throw new IllegalStateException("Conversion hook '" + hook.id() + "' javaHook read "
                        + batch.size() + " row(s) in batch " + batchOrdinal + " but wrote back none -- "
                        + "refusing rather than looping forever re-selecting the same unresolved rows.");
            }

            MigrationPhaseJournal.PhaseKey key = new MigrationPhaseJournal.PhaseKey(migrationId, hook.id(), batchOrdinal);
            String batchHash = hashBatch(batch, primaryKeyColumn);
            writeBatchJournaled(dataSource, hook, key, batchHash, table, primaryKeyColumn, claimedColumns, writes);

            batchOrdinal++;
        }
    }

    private static void writeBatchJournaled(DataSource dataSource, ConversionHookRunner.Hook hook,
            MigrationPhaseJournal.PhaseKey key, String batchHash, String table, String primaryKeyColumn,
            List<String> claimedColumns, List<Map<String, Object>> writes) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                MigrationPhaseJournal.recordStarted(connection, key, "JAVA_HOOK_BATCH", batchHash);
                writeRows(connection, table, primaryKeyColumn, claimedColumns, writes, hook);
                MigrationPhaseJournal.recordCompleted(connection, key);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                safeRollback(connection);
                throw new IllegalStateException("Conversion hook '" + hook.id() + "' failed writing batch "
                        + key.phaseOrdinal() + ": " + failure.getMessage(), failure);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Conversion hook '" + hook.id()
                    + "' failed opening a connection to write batch " + key.phaseOrdinal() + ": "
                    + exception.getMessage(), exception);
        }
    }

    /** Dispatches the batch through the SAME pool/registry/policy-evaluator machinery a
     *  {@code plugin:java-source} invoke already uses -- {@link ManifestDrivenJavaSourcePluginHandler}
     *  resolves the hook's FQCN/method from {@code java-source-runtime-refs.json} by capability, then
     *  reflects into it, exactly as it does for any other mount. The hook issues no callback, so the
     *  dispatcher passed to {@link PluginIpcHostSession} unconditionally refuses one -- mirroring
     *  {@code PluginIpcCapabilityHandler}'s own {@code plugin:java-source} precedent (that mount kind
     *  has never been able to call back into the host either). */
    private static List<Map<String, Object>> invokeChild(ConversionHookRunner.JavaHookRuntimeContext context,
            ConversionHookRunner.Hook hook, List<Map<String, Object>> batch) {
        String capability = conversionHookCapability(hook);
        String operation = hook.javaHookMethod();
        RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution =
                context.registry().requireContribution(capability, operation, ADAPTER_ID);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("rows", batch);
        CapabilityCall call = new CapabilityCall(capability, null, ADAPTER_ID, operation, List.of(input));
        PluginIpcHostSession hostSession = new PluginIpcHostSession(
                context.registry(), context.policyEvaluator(), JavaMigrationHookRunner::rejectCallback);

        CapabilityResult result;
        try {
            result = context.pool().invoke(hostSession, contribution, call, Map.of(),
                    ManifestDrivenJavaSourcePluginHandler.class.getName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Conversion hook '" + hook.id()
                    + "' javaHook invocation was interrupted", exception);
        }
        if (!result.ok()) {
            throw new IllegalStateException("Conversion hook '" + hook.id() + "' javaHook invocation failed: "
                    + result.error().code() + " " + result.error().message());
        }
        if (!(result.value() instanceof Map<?, ?> resultMap)) {
            throw new IllegalStateException("Conversion hook '" + hook.id()
                    + "' javaHook method must return a Map, got: "
                    + (result.value() == null ? "null" : result.value().getClass().getName()));
        }
        Object rows = resultMap.get("rows");
        if (!(rows instanceof List<?> rowsList)) {
            throw new IllegalStateException("Conversion hook '" + hook.id()
                    + "' javaHook method must return {\"rows\": [...]}, got: " + resultMap);
        }
        List<Map<String, Object>> writes = new ArrayList<>();
        for (Object element : rowsList) {
            if (!(element instanceof Map<?, ?> rowMap)) {
                throw new IllegalStateException("Conversion hook '" + hook.id()
                        + "' javaHook method returned a non-Map row entry: " + element);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedRow = (Map<String, Object>) rowMap;
            writes.add(typedRow);
        }
        return writes;
    }

    private static CapabilityResult rejectCallback(CapabilityCall call) {
        return CapabilityResult.failure(
                "JAVA_MIGRATION_HOOK_CALLBACK_NOT_SUPPORTED",
                "javaHook migration hooks cannot call back into the host: " + call.capability() + "." + call.operation(),
                CapabilityErrorKind.PERMANENT,
                Map.of("capability", call.capability(), "operation", call.operation())
        );
    }

    private static List<Map<String, Object>> readNextBatch(Connection connection, String table,
            String primaryKeyColumn, List<String> claimedColumns, int batchSize) throws SQLException {
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < claimedColumns.size(); i++) {
            if (i > 0) {
                where.append(" OR ");
            }
            where.append(claimedColumns.get(i)).append(" IS NULL");
        }
        // STOR-1: dialect-bound pagination lives in com.npdev.kernel.storage.sql -- MigrationMutex's
        // own documented gotcha (SqlDialects.active() defaults to Postgres when nothing pinned it) is
        // why this asks the CONNECTION's own dialect, not the process-wide ambient one.
        PaginationClause pagination = SqlDialects.forConnection(connection).limitOnly();
        // No projection list: a javaHook's whole value is reading columns the compiler cannot see in
        // advance (unlike every declarative op, which the generator emits an explicit column list
        // for) -- there is no fixed set to project here. Runs only during migration, not on ordinary
        // request traffic, which is what A3 (STOR-24)'s "never select *" discipline targets.
        String sql = "SELECT * FROM " + table + " WHERE " + where + " ORDER BY " + primaryKeyColumn
                + " " + pagination.clause();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (int value : pagination.values(batchSize, 0)) {
                statement.setInt(index++, value);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metadata.getColumnLabel(i), jsonSafe(resultSet.getObject(i)));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static void writeRows(Connection connection, String table, String primaryKeyColumn,
            List<String> claimedColumns, List<Map<String, Object>> writes, ConversionHookRunner.Hook hook)
            throws SQLException {
        StringBuilder setClause = new StringBuilder();
        for (int i = 0; i < claimedColumns.size(); i++) {
            if (i > 0) {
                setClause.append(", ");
            }
            setClause.append(claimedColumns.get(i)).append(" = ?");
        }
        String sql = "UPDATE " + table + " SET " + setClause + " WHERE " + primaryKeyColumn + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map<String, Object> write : writes) {
                if (!write.containsKey(primaryKeyColumn) && !write.containsKey("id")) {
                    throw new IllegalStateException("Conversion hook '" + hook.id()
                            + "' javaHook returned a row with no '" + primaryKeyColumn + "' (primary key): " + write);
                }
                Object primaryKeyValue = write.containsKey(primaryKeyColumn) ? write.get(primaryKeyColumn) : write.get("id");
                int index = 1;
                for (String column : claimedColumns) {
                    statement.setObject(index++, write.get(column));
                }
                statement.setObject(index, primaryKeyValue);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static String primaryKeyColumn(DataSource dataSource, String table, ConversionHookRunner.Hook hook) {
        CurrentSchema schema = new CurrentSchemaReader().read(dataSource);
        var currentTable = schema.tables().get(table.toLowerCase(Locale.ROOT));
        if (currentTable == null || currentTable.primaryKeyColumns().isEmpty()) {
            throw new IllegalStateException("Conversion hook '" + hook.id()
                    + "' javaHook targets table '" + table + "', which has no primary key the platform can find.");
        }
        return currentTable.primaryKeyColumns().get(0);
    }

    private static String parseTable(ConversionHookRunner.Hook hook) {
        for (String claim : hook.claims()) {
            String[] parts = claim.split(":", 3);
            if (parts.length == 3) {
                return parts[1];
            }
        }
        throw new IllegalStateException("Conversion hook '" + hook.id() + "' javaHook has no parseable claims.");
    }

    private static List<String> parseColumns(ConversionHookRunner.Hook hook) {
        List<String> columns = new ArrayList<>();
        for (String claim : hook.claims()) {
            String[] parts = claim.split(":", 3);
            if (parts.length == 3) {
                columns.add(parts[2]);
            }
        }
        return columns;
    }

    private static String conversionHookCapability(ConversionHookRunner.Hook hook) {
        return "conversionHook:" + hook.id();
    }

    private static Object jsonSafe(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        return value.toString();
    }

    private static String hashBatch(List<Map<String, Object>> batch, String primaryKeyColumn) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : batch) {
            Object idValue = row.get(primaryKeyColumn);
            ids.add(idValue == null ? row.get("id") == null ? "" : row.get("id").toString() : idValue.toString());
        }
        ids.sort(String::compareTo);
        return sha256Hex(String.join(",", ids));
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort rollback; the caller already has the real failure to propagate
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private static int resolveBatchSize() {
        String configured = System.getProperty(BATCH_SIZE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_BATCH_SIZE;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : DEFAULT_BATCH_SIZE;
        } catch (NumberFormatException exception) {
            return DEFAULT_BATCH_SIZE;
        }
    }
}
