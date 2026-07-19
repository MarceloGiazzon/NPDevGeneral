package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LNCH-1 remediation R1 (finding F3): the permanent cross-producer ratchet. Proves that for EVERY
 * SQL type the platform emits, the acknowledgment-token stable string an operator's boot computes
 * from LIVE JDBC metadata (the executor / {@link SchemaDeltaReport} side) is byte-identical to the
 * one the generator's {@code MigrationPlanEmitter} preview computes from the MODEL-declared type --
 * because both now route their type strings through the one shared
 * {@link SqlTypeNormalization#normalize}.
 *
 * <p>Before R1 the executor normalized live metadata via its own package-private method while the
 * generator put raw model-declared strings into the destructive items, so any type whose live
 * spelling diverged from its model spelling (H2's {@code CHARACTER VARYING} for {@code VARCHAR(n)},
 * Postgres's {@code int8} for {@code BIGINT}, etc.) produced two different tokens -> a refused boot
 * whose "expected token" the plan never showed. This test fails the instant a future new type or
 * engine alias reintroduces that drift, with both normalized spellings printed side by side.
 *
 * <p><b>Generator-side note:</b> {@code MigrationPlanEmitter} lives in the generator module, which is
 * not on RuntimeHost's test classpath, so the generator half is reconstructed here through the exact
 * shared primitives the emitter now uses ({@link SqlTypeNormalization} + the {@link SchemaDeltaItem}
 * records + {@link DestructiveAckToken}). The generator's own construction of these items is covered
 * by {@code MigrationPlanEmitterTest} in that module; the property THIS test pins is the agreement of
 * the two normalizations against a REAL database, which only RuntimeHost can exercise on H2.
 */
class TokenAgreementConformanceTest {

    /** Every distinct SQL type {@code com.npdev.dsl.v1.compiled.SqlTypeSupport#sqlType} emits (the
     * platform's model-declared type catalog). If SqlTypeSupport gains a new mapping, add it here --
     * this test is the ratchet that keeps the two token producers in agreement for it. */
    private static final List<String> MODEL_TYPE_CATALOG = List.of(
            "UUID",
            "INTEGER",
            "BIGINT",
            "BOOLEAN",
            "DATE",
            "TIMESTAMP WITH TIME ZONE",
            "JSONB",
            "VARCHAR(255)",
            "NUMERIC(19,2)");

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void normalizerAgreesLiveVsModelDeclaredForEveryCatalogType() throws SQLException {
        for (int i = 0; i < MODEL_TYPE_CATALOG.size(); i++) {
            String modelType = MODEL_TYPE_CATALOG.get(i);
            String table = "probe_" + i;
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, c " + h2Ddl(modelType) + ")");
                DatabaseMetaData metadata = connection.getMetaData();
                Map<String, String> liveTypes = SchemaLifecycleExecutor.readActualColumnTypes(metadata, table);
                String liveType = liveTypes.get("c");
                String normalizedLive = SqlTypeNormalization.normalize(liveType);
                String normalizedModel = SqlTypeNormalization.normalize(modelType);
                assertEquals(normalizedModel, normalizedLive,
                        "normalization must agree for type '" + modelType + "': model-declared normalized to '"
                                + normalizedModel + "' but live JDBC type '" + liveType + "' normalized to '"
                                + normalizedLive + "'");
            }
        }
    }

    @Test
    void dropColumnTokenIsByteIdenticalAcrossProducersForEveryCatalogType() throws SQLException {
        // Build one probe table carrying a column of every catalog type, then drop them all.
        StringBuilder ddl = new StringBuilder("CREATE TABLE probe (id BIGINT PRIMARY KEY");
        Map<String, String> columnToModelType = new LinkedHashMap<>();
        for (int i = 0; i < MODEL_TYPE_CATALOG.size(); i++) {
            String column = "c" + i;
            String modelType = MODEL_TYPE_CATALOG.get(i);
            columnToModelType.put(column, modelType);
            ddl.append(", ").append(column).append(' ').append(h2Ddl(modelType));
        }
        ddl.append(')');
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(ddl.toString());
        }

        // Executor side: the manifest keeps only 'id', so SchemaDeltaReport itemizes a DROP_COLUMN for
        // every typed column, reading each type live and normalizing it (SchemaDeltaReport#normalizedOrRaw).
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("probe", List.of("id")), Map.of("probe", Map.of("id", "BIGINT")));
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);
        String executorToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        // Generator side (reconstructed): MigrationPlanEmitter constructs DropColumn(table, column,
        // normalize(modelDeclaredType)) for each dropped column -- exactly what it now does after R1.
        List<String> generatorStableStrings = new ArrayList<>();
        for (Map.Entry<String, String> entry : columnToModelType.entrySet()) {
            String normalizedModel = SqlTypeNormalization.normalize(entry.getValue());
            generatorStableStrings.add(new SchemaDeltaItem.DropColumn("probe", entry.getKey(), normalizedModel).stableString());
        }
        String generatorToken = DestructiveAckToken.compute("sha256:new", generatorStableStrings);

        assertEquals(generatorToken, executorToken,
                "DROP_COLUMN token must be byte-identical across producers.\n  executor items="
                        + report.stableStrings() + "\n  generator items=" + generatorStableStrings);
    }

    @Test
    void dropTableTokenIsByteIdenticalAcrossProducersRegardlessOfRowCount() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE gadgets (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO gadgets (id) VALUES (1)");
            statement.execute("INSERT INTO gadgets (id) VALUES (2)");
        }
        // Executor side: gadgets is not in the manifest -> DROP_TABLE with the live row count (2).
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id")), Map.of("widgets", Map.of("id", "BIGINT")));
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);
        String executorToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

        // Generator side: no live DB -> DropTable(gadgets, -1). R1/F2: the row count is out of the hash,
        // so the two tokens must match despite gadgets having 2 live rows.
        String generatorToken = DestructiveAckToken.compute("sha256:new",
                List.of(new SchemaDeltaItem.DropTable("gadgets", -1L).stableString()));

        assertEquals(generatorToken, executorToken,
                "DROP_TABLE token must not depend on the live row count (executor items=" + report.stableStrings() + ")");
    }

    @Test
    void narrowTypeTokenIsByteIdenticalAcrossProducersForEachComparisonBranch() throws SQLException {
        // One shared column per distinct TypeChangeMatrix branch, live type NARROWER than the model
        // expects so SchemaDeltaReport itemizes a NARROW_TYPE.
        record Narrowing(String table, String liveDdl, String oldModelType, String newModelType) {
        }
        List<Narrowing> cases = List.of(
                new Narrowing("nt_varchar", "VARCHAR(100)", "VARCHAR(100)", "VARCHAR(50)"),   // varchar-length
                new Narrowing("nt_numeric", "NUMERIC(10,2)", "NUMERIC(10,2)", "NUMERIC(5,2)"), // numeric precision
                new Narrowing("nt_family", "VARCHAR(50)", "VARCHAR(50)", "INTEGER"));          // family mismatch

        for (Narrowing c : cases) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + c.table() + " (id BIGINT PRIMARY KEY, c " + c.liveDdl() + ")");
            }
            // The manifest declares ONLY this case's table, so SchemaDeltaReport must not see the
            // other cases' tables as orphan DROP_TABLE items -- drop each after use (below).
            SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                    Map.of(c.table(), List.of("id", "c")),
                    Map.of(c.table(), Map.of("id", "BIGINT", "c", c.newModelType())));
            SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);
            String executorToken = DestructiveAckToken.compute("sha256:new", report.stableStrings());

            // Generator side: NarrowType(table, c, normalize(oldModelType), normalize(newModelType)).
            String generatorStable = new SchemaDeltaItem.NarrowType(
                    c.table(), "c",
                    SqlTypeNormalization.normalize(c.oldModelType()),
                    SqlTypeNormalization.normalize(c.newModelType())).stableString();
            String generatorToken = DestructiveAckToken.compute("sha256:new", List.of(generatorStable));

            assertEquals(generatorToken, executorToken,
                    "NARROW_TYPE token must be byte-identical for " + c.table() + " (executor items="
                            + report.stableStrings() + ", generator=" + generatorStable + ")");

            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + c.table());
            }
        }
    }

    /** H2 DDL rendering, mirroring {@code SchemaRealizationEmitter#renderType} for the H2 engine
     * (only JSONB/JSON is remapped; everything else is emitted verbatim). */
    private static String h2Ddl(String modelType) {
        if ("JSONB".equalsIgnoreCase(modelType) || "JSON".equalsIgnoreCase(modelType)) {
            return "JSON";
        }
        return modelType;
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:new", List.of(), List.copyOf(businessTableColumns.keySet()),
                businessTableColumns, Map.of(), businessTableColumnTypes,
                Map.of(), Map.of(), false,
                "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "",
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids an H2-specific compile dep. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
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
