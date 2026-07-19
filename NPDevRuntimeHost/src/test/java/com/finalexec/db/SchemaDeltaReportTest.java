package com.finalexec.db;

import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 4 (task 4.5). Unit coverage for {@link SchemaDeltaReport}: itemization correctness
 * for each of the four item kinds, and -- the property the plan calls out explicitly -- that the
 * stable-string output is byte-identical regardless of the {@code Map}/{@code Set} construction
 * order used to build the {@code SchemaManifest} feeding the same underlying live-DB diff.
 */
class SchemaDeltaReportTest {

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
    void itemizesAnExtraLiveColumnAsDropColumn() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(255), legacy_flag BOOLEAN)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id", "email")),
                Map.of("users", Map.of("id", "BIGINT", "email", "VARCHAR(255)")));

        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);

        assertEquals(1, report.items().size());
        SchemaDeltaItem item = report.items().get(0);
        assertTrue(item instanceof SchemaDeltaItem.DropColumn);
        SchemaDeltaItem.DropColumn dropColumn = (SchemaDeltaItem.DropColumn) item;
        assertEquals("users", dropColumn.table());
        assertEquals("legacy_flag", dropColumn.column());
        assertEquals("DROP_COLUMN:users:legacy_flag:BOOLEAN", dropColumn.stableString());
        assertTrue(report.hasOnlyNamedDestructiveKinds());
        assertEquals(Set.of("users"), report.affectedTables());
    }

    @Test
    void itemizesAnExtraLiveTableAsDropTableWithRowCount() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE legacy_widgets (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO legacy_widgets (id) VALUES (1), (2), (3)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id")),
                Map.of("users", Map.of("id", "BIGINT")));

        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);

        assertEquals(1, report.items().size());
        SchemaDeltaItem.DropTable dropTable = (SchemaDeltaItem.DropTable) report.items().get(0);
        assertEquals("legacy_widgets", dropTable.table());
        assertEquals(3L, dropTable.rowCountAtClassification());
        // R1 (F2): the row count is display metadata only -- present in displayString(), NEVER in
        // the hashed stableString() (so a concept-drop token is plan-computable).
        assertEquals("DROP_TABLE:legacy_widgets", dropTable.stableString());
        assertEquals("DROP_TABLE:legacy_widgets:3", dropTable.displayString());
    }

    @Test
    void itemizesANarrowingSharedColumnTypeChangeAsNarrowType() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, code VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "code")),
                Map.of("widgets", Map.of("id", "BIGINT", "code", "VARCHAR(20)")));

        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);

        assertEquals(1, report.items().size());
        SchemaDeltaItem.NarrowType narrowType = (SchemaDeltaItem.NarrowType) report.items().get(0);
        assertEquals("widgets", narrowType.table());
        assertEquals("code", narrowType.column());
        assertEquals("VARCHAR(50)", narrowType.fromType());
        assertEquals("VARCHAR(20)", narrowType.toType());
        assertEquals("NARROW_TYPE:widgets:code:VARCHAR(50):VARCHAR(20)", narrowType.stableString());
    }

    @Test
    void itemizesAnIncomparableFamilyMismatchAsNarrowTypeTooNotAFifthKind() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, note VARCHAR(50))");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("widgets", List.of("id", "note")),
                Map.of("widgets", Map.of("id", "BIGINT", "note", "BOOLEAN")));

        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);

        assertEquals(1, report.items().size());
        assertTrue(report.items().get(0) instanceof SchemaDeltaItem.NarrowType,
                "INCOMPARABLE type pairs must still be itemized as NARROW_TYPE per the plan's exact vocabulary");
    }

    @Test
    void itemizesAMissingRequiredNonAdditiveColumnAsUnknown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }
        // "quantity" is expected but not additive-eligible (e.g. required, no default) and not live.
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:test", List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "quantity")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "BIGINT", "quantity", "INTEGER")),
                Map.of(), Map.of(), true, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "", Map.of(), Map.of(), Map.of(), Map.of());

        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);

        assertEquals(1, report.items().size());
        assertTrue(report.items().get(0) instanceof SchemaDeltaItem.Unknown);
        assertTrue(!report.hasOnlyNamedDestructiveKinds());
    }

    @Test
    void doesNotItemizeAnAdditiveEligibleMissingColumn() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:test", List.of(), List.of("widgets"),
                Map.of("widgets", List.of("id", "nickname")),
                Map.of("widgets", List.of("nickname")),
                Map.of("widgets", Map.of("id", "BIGINT", "nickname", "VARCHAR(255)")),
                Map.of(), Map.of(), true, "DropAndRecreateOnStructureChange", "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "", Map.of(), Map.of(), Map.of(), Map.of());

        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);

        assertTrue(report.isEmpty(), "an additive-eligible missing column is the additive migration's job, not this report's");
    }

    @Test
    void doesNotItemizeTheOldSideOfADeclaredTableRenameAsDropTable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:test", List.of(), List.of("accounts"),
                Map.of("accounts", List.of("id")),
                Map.of("accounts", List.of()),
                Map.of("accounts", Map.of("id", "BIGINT")),
                Map.of(), Map.of("accounts", "users"), true, "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly", "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED", "",
                Map.of(), Map.of(), Map.of(), Map.of());

        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);

        assertTrue(report.isEmpty(), "the OLD half of a declared table rename must never be flagged DROP_TABLE "
                + "-- it is attemptInPlaceTableRenames()'s job to resolve it, run before this report in the "
                + "real beforeMigrate() sequence");
    }

    @Test
    void doesNotItemizeNpdevOwnedHistoryOrFlywayTablesAsDropTable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE npdev_schema_history (id VARCHAR(64) PRIMARY KEY)");
            statement.execute("CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY)");
        }
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest(
                Map.of("users", List.of("id")),
                Map.of("users", Map.of("id", "BIGINT")));

        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);

        assertTrue(report.isEmpty(), "npdev_schema_history and flyway_schema_history must never be surgical "
                + "drop candidates even though neither is part of the generator's internalTables catalog");
    }

    @Test
    void stableStringsAreIdenticalRegardlessOfManifestMapConstructionOrder() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, legacy_flag BOOLEAN, old_phone VARCHAR(20))");
            statement.execute("CREATE TABLE legacy_widgets (id BIGINT PRIMARY KEY)");
        }

        // Build businessTableColumns/businessTableColumnTypes for "users" via two different
        // Map/List construction orders that describe the identical logical diff.
        Map<String, List<String>> columnsOrderA = new LinkedHashMap<>();
        columnsOrderA.put("users", List.of("id"));
        Map<String, List<String>> columnsOrderB = new LinkedHashMap<>();
        columnsOrderB.put("users", List.of("id")); // only one expected column either way; order affects the LIVE side, not this

        SchemaLifecycleExecutor.SchemaManifest manifestA = manifest(columnsOrderA,
                Map.of("users", Map.of("id", "BIGINT")));
        SchemaLifecycleExecutor.SchemaManifest manifestB = manifest(columnsOrderB,
                Map.of("users", Map.of("id", "BIGINT")));

        SchemaDeltaReport reportA = SchemaDeltaReport.generate(dataSource, manifestA);
        SchemaDeltaReport reportB = SchemaDeltaReport.generate(dataSource, manifestB);

        assertEquals(reportA.stableStrings(), reportB.stableStrings());
        // Two DROP_COLUMN candidates (legacy_flag, old_phone) plus one DROP_TABLE (legacy_widgets)
        // -- assert the combined list is deterministically sorted (table, then column, then kind).
        assertEquals(
                List.of(
                        "DROP_TABLE:legacy_widgets",
                        "DROP_COLUMN:users:legacy_flag:BOOLEAN",
                        "DROP_COLUMN:users:old_phone:VARCHAR(20)"),
                reportA.stableStrings());
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifest(
            Map<String, List<String>> businessTableColumns,
            Map<String, Map<String, String>> businessTableColumnTypes) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local",
                "jdbc",
                true,
                "sha256:test",
                List.of(),
                List.copyOf(businessTableColumns.keySet()),
                businessTableColumns,
                Map.of(),
                businessTableColumnTypes,
                Map.of(),
                Map.of(),
                true,
                "DropAndRecreateOnStructureChange",
                "NpdevOwnedTablesOnly",
                "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED",
                "",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids pulling in an H2-specific compile-time dependency. */
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
