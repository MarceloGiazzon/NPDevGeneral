package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifest;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifestLoader;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B1 (REAL_LIFT_PLAN_2026-09-03, B13): proves {@link ConversionHookRunner}'s javaHook path end to
 * end against a REAL H2 table and the REAL compiled {@code OrderSummaryHook} class, dispatched
 * through the REAL {@link PluginIpcChildProcessPool} -- the same "real generated plugin" proof shape
 * {@code PluginIpcCapabilityHandlerRealPluginTest} uses for {@code plugin:java-source}'s
 * {@code auditLog} mount. Requires the currently assembled sample app to be dsl-conformance-max
 * (skips gracefully otherwise, same {@code assumeTrue} convention).
 */
class ConversionHookRunnerJavaHookTest {

    private static final String HOOK_ID = "0008-java-hook-order-summary";

    @Test
    void runsARealJavaHookThroughTheIsolatedPoolAndBackfillsTheClaimedColumn() throws Exception {
        JavaSourceRuntimeRefManifest.Entry entry = requireManifestEntry();
        String claimKey = requireClaimKey();
        String[] claimParts = claimKey.split(":", 3);
        assertEquals("ADD_REQUIRED_COLUMN", claimParts[0], "unexpected claim shape: " + claimKey);
        String table = claimParts[1];
        String column = claimParts[2];

        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id UUID PRIMARY KEY, priority_number INT, "
                    + "region_label VARCHAR(255), " + column + " VARCHAR(255))");
            statement.execute("INSERT INTO " + table
                    + " (id, priority_number, region_label) VALUES (RANDOM_UUID(), 1, 'North')");
            statement.execute("INSERT INTO " + table
                    + " (id, priority_number, region_label) VALUES (RANDOM_UUID(), 5, 'South')");
        }

        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(table, column);
        List<String[]> history = new ArrayList<>();
        ConversionHookRunner.HistoryWriter historyWriter =
                (label, outcome, details) -> history.add(new String[] {label, outcome, String.valueOf(details)});

        RuntimePluginAdapterRegistry registry = registryFor(entry);
        PluginExecutionPolicyEvaluator policyEvaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");
        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(1, 5, Duration.ofMinutes(10))) {
            ConversionHookRunner.JavaHookRuntimeContext context =
                    new ConversionHookRunner.JavaHookRuntimeContext(pool, registry, policyEvaluator);

            boolean applied = ConversionHookRunner.run(dataSource, manifest, historyWriter, context);

            assertTrue(applied, "expected the javaHook to be selected and applied: " + history);
        }

        Map<Integer, String> summaryByPriority = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT priority_number, " + column + " FROM " + table)) {
            while (resultSet.next()) {
                summaryByPriority.put(resultSet.getInt(1), resultSet.getString(2));
            }
        }
        assertEquals("URGENT-North", summaryByPriority.get(1),
                "priority 1 (<= 1) must be classified URGENT by the real OrderSummaryHook");
        assertEquals("STANDARD-South", summaryByPriority.get(5),
                "priority 5 (> 1) must be classified STANDARD by the real OrderSummaryHook");
    }

    private static JavaSourceRuntimeRefManifest.Entry requireManifestEntry() {
        JavaSourceRuntimeRefManifest manifest = new JavaSourceRuntimeRefManifestLoader(new ObjectMapper()).load();
        Optional<JavaSourceRuntimeRefManifest.Entry> entry = manifest.entryForRuntimeRef(HOOK_ID);
        assumeTrue(
                entry.isPresent(),
                "No '" + HOOK_ID + "' entry in java-source-runtime-refs.json -- the currently assembled sample "
                        + "app is not dsl-conformance-max, so this real-javaHook proof has nothing to run "
                        + "against. Regenerate against dsl-conformance-max to exercise it."
        );
        return entry.get();
    }

    private static String requireClaimKey() {
        for (Map.Entry<String, String> entry : ConversionHookRunner.loadClaimIndex().entrySet()) {
            if (HOOK_ID.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("No claim indexed for hook '" + HOOK_ID
                + "' -- the currently assembled sample app's db/conversion-hooks output is not on the "
                + "test classpath.");
    }

    private static RuntimePluginAdapterRegistry registryFor(JavaSourceRuntimeRefManifest.Entry entry) {
        String operation = entry.methodByOperation().keySet().iterator().next();
        RuntimePluginManifest manifest = new RuntimePluginManifest(
                "test-manifest.json", "1",
                List.of(new RuntimePluginManifest.PluginContribution(
                        entry.pluginId(), "Java migration hook test plugin", "1.0.0", true,
                        List.of(new RuntimePluginManifest.AdapterContribution(
                                entry.capability(), operation, entry.adapterId(),
                                entry.capability() + "." + operation,
                                new RuntimePluginManifest.ImplementationRef("runtimeRef", entry.runtimeRef())
                        ))
                ))
        );
        return new RuntimePluginAdapterRegistry(manifest);
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestFor(String table, String claimedColumn) {
        Map<String, String> columnTypes = new LinkedHashMap<>();
        columnTypes.put("id", "UUID");
        columnTypes.put("priority_number", "INTEGER");
        columnTypes.put("region_label", "VARCHAR(255)");
        columnTypes.put(claimedColumn, "VARCHAR(255)");
        List<String> desiredColumns = List.copyOf(columnTypes.keySet());
        List<String> liveColumns = List.of("id", "priority_number", "region_label"); // claimedColumn is missing/NEEDS_HOOK
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:b1-java-hook-test",
                List.of(), List.of(table),
                Map.of(table, desiredColumns),
                Map.of(table, List.of()), // additiveColumns empty -> the missing column is NEEDS_HOOK
                Map.of(table, columnTypes),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of(table, List.of(claimedColumn)), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} over {@link DriverManager} (no H2-specific compile dependency),
     *  same shape every other test {@code UrlDataSource} in this package already uses. */
    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
