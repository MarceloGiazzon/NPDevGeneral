package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifest;
import com.finalexec.npdev.service.pluginipc.JavaSourceRuntimeRefManifestLoader;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * B1 (REAL_LIFT_PLAN_2026-09-03, B13 done-when #4): a child-process harness for {@code
 * MigrationKillMidJavaHookCrashResumeTest} -- mirrors {@link MigrationKillMidPhaseHarness}'s own
 * "run a known prefix through the REAL production entry point, force a durable checkpoint, then
 * {@link Runtime#halt}" trick (A1's own instruction: proving resumability needs an ACTUAL killed
 * process, not a simulated exception), applied to {@link JavaMigrationHookRunner}'s batch loop
 * instead of {@link ConversionHookPhaseRunner}'s phase loop.
 *
 * <p>Requires the currently assembled sample app to be dsl-conformance-max (its {@code
 * OrderSummaryHook} + {@code java-source-runtime-refs.json} entry must be on this process's own
 * classpath) -- {@code MigrationKillMidJavaHookCrashResumeTest} skips gracefully otherwise, same
 * {@code assumeTrue} convention every other real-plugin proof in this codebase uses.
 *
 * <ul>
 *   <li>{@code crash <dbPath> <maxBatches>} -- creates a 4-row table (batch size forced to 1 via
 *       {@code npdev.schema.conversionHooks.javaHookBatchSize}, so exactly 4 batches exist), runs
 *       {@link JavaMigrationHookRunner#run} directly (bypassing {@code ConversionHookRunner.run}'s own
 *       hook-selection loop, which has no seam to stop after an exact batch count -- same reasoning
 *       {@link MigrationKillMidPhaseHarness} gives for calling {@code ConversionHookPhaseRunner}
 *       directly) for exactly {@code maxBatches} batches through the REAL pooled plugin process, forces
 *       a durable {@code CHECKPOINT SYNC}, then halts.</li>
 *   <li>{@code resume <dbPath>} -- runs the REAL {@code ConversionHookRunner.run} boot path, exactly
 *       what a real second boot does.</li>
 * </ul>
 */
final class MigrationKillMidJavaHookHarness {

    private static final String HOOK_ID = "0008-java-hook-order-summary";

    private MigrationKillMidJavaHookHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: <crash|resume> <dbPath> [maxBatches]");
        }
        String mode = args[0];
        String dbPath = args[1];
        String url = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE";
        DataSource dataSource = new SimpleUrlDataSource(url);
        SqlDialects.setActive(H2Dialect.INSTANCE);
        System.setProperty("npdev.schema.conversionHooks.javaHookBatchSize", "1");

        JavaSourceRuntimeRefManifest.Entry entry = requireManifestEntry();
        String claimKey = requireClaimKey();
        String[] claimParts = claimKey.split(":", 3);
        String table = claimParts[1];
        String column = claimParts[2];

        switch (mode) {
            case "crash" -> runCrashMode(dataSource, entry, table, column, claimKey, Integer.parseInt(args[2]));
            case "resume" -> runResumeMode(dataSource, table, column);
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static void runCrashMode(DataSource dataSource, JavaSourceRuntimeRefManifest.Entry entry,
            String table, String column, String claimKey, int maxBatches) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id UUID PRIMARY KEY, priority_number INT, "
                    + "region_label VARCHAR(255), " + column + " VARCHAR(255))");
            for (int i = 1; i <= 4; i++) {
                statement.execute("INSERT INTO " + table
                        + " (id, priority_number, region_label) VALUES (RANDOM_UUID(), " + i + ", 'Region" + i + "')");
            }
        }

        String migrationId = migrationIdFor(dataSource, table, column);
        String method = entry.methodByOperation().keySet().iterator().next();
        ConversionHookRunner.Hook hook = new ConversionHookRunner.Hook(
                HOOK_ID, List.of(claimKey), null, 0, null, null, null, entry.mainClass(), method);

        RuntimePluginAdapterRegistry registry = registryFor(entry, method);
        PluginExecutionPolicyEvaluator policyEvaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");
        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(1, 5, Duration.ofMinutes(10))) {
            ConversionHookRunner.JavaHookRuntimeContext context =
                    new ConversionHookRunner.JavaHookRuntimeContext(pool, registry, policyEvaluator);
            JavaMigrationHookRunner.run(dataSource, migrationId, hook, context, maxBatches);
        }

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CHECKPOINT SYNC"); // force the durable flush a real halt() must survive
        }
        System.out.println("CRASH_HARNESS: completed maxBatches=" + maxBatches + "; halting now.");
        System.out.flush();
        Runtime.getRuntime().halt(137); // never returns -- no shutdown hook, no finally block anywhere runs
    }

    private static void runResumeMode(DataSource dataSource, String table, String column) throws Exception {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(table, column);
        List<String[]> history = new ArrayList<>();
        ConversionHookRunner.HistoryWriter historyWriter =
                (label, outcome, details) -> history.add(new String[] {label, outcome, String.valueOf(details)});

        JavaSourceRuntimeRefManifest.Entry entry = requireManifestEntry();
        String method = entry.methodByOperation().keySet().iterator().next();
        RuntimePluginAdapterRegistry registry = registryFor(entry, method);
        PluginExecutionPolicyEvaluator policyEvaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");
        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(1, 5, Duration.ofMinutes(10))) {
            ConversionHookRunner.JavaHookRuntimeContext context =
                    new ConversionHookRunner.JavaHookRuntimeContext(pool, registry, policyEvaluator);
            ConversionHookRunner.run(dataSource, manifest, historyWriter, context);
        }

        for (String[] row : history) {
            System.out.println("RESUME_HARNESS: " + row[0] + " " + row[1] + " " + row[2]);
        }
        System.out.println("RESUME_HARNESS: DONE");
    }

    private static String migrationIdFor(DataSource dataSource, String table, String column) {
        SchemaLifecycleExecutor.SchemaManifest manifest = manifestFor(table, column);
        return MigrationPhaseJournal.migrationId(
                SchemaLifecycleExecutor.readStoredFingerprintPublic(dataSource), manifest.schemaFingerprint());
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestFor(String table, String claimedColumn) {
        Map<String, String> columnTypes = new LinkedHashMap<>();
        columnTypes.put("id", "UUID");
        columnTypes.put("priority_number", "INTEGER");
        columnTypes.put("region_label", "VARCHAR(255)");
        columnTypes.put(claimedColumn, "VARCHAR(255)");
        List<String> desiredColumns = List.copyOf(columnTypes.keySet());
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:b1-java-hook-kill-test",
                List.of(), List.of(table),
                Map.of(table, desiredColumns),
                Map.of(table, List.of()),
                Map.of(table, columnTypes),
                Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                Map.of(table, List.of(claimedColumn)), Map.of(), Map.of(), Map.of());
    }

    private static JavaSourceRuntimeRefManifest.Entry requireManifestEntry() {
        JavaSourceRuntimeRefManifest manifest = new JavaSourceRuntimeRefManifestLoader(new ObjectMapper()).load();
        Optional<JavaSourceRuntimeRefManifest.Entry> entry = manifest.entryForRuntimeRef(HOOK_ID);
        if (entry.isEmpty()) {
            throw new IllegalStateException("No '" + HOOK_ID + "' entry in java-source-runtime-refs.json -- "
                    + "the currently assembled sample app is not dsl-conformance-max.");
        }
        return entry.get();
    }

    private static String requireClaimKey() {
        for (Map.Entry<String, String> entry : ConversionHookRunner.loadClaimIndex().entrySet()) {
            if (HOOK_ID.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("No claim indexed for hook '" + HOOK_ID + "'.");
    }

    private static RuntimePluginAdapterRegistry registryFor(JavaSourceRuntimeRefManifest.Entry entry, String operation) {
        RuntimePluginManifest manifest = new RuntimePluginManifest(
                "test-manifest.json", "1",
                List.of(new RuntimePluginManifest.PluginContribution(
                        entry.pluginId(), "Java migration hook kill-test plugin", "1.0.0", true,
                        List.of(new RuntimePluginManifest.AdapterContribution(
                                entry.capability(), operation, entry.adapterId(),
                                entry.capability() + "." + operation,
                                new RuntimePluginManifest.ImplementationRef("runtimeRef", entry.runtimeRef())
                        ))
                ))
        );
        return new RuntimePluginAdapterRegistry(manifest);
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- no H2-specific compile dependency,
     *  mirroring {@link MigrationKillMidPhaseHarness}'s own identical helper. */
    private static final class SimpleUrlDataSource implements DataSource {
        private final String url;

        private SimpleUrlDataSource(String url) {
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
