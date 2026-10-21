package com.finalexec.db;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIVE-1 against a real H2 database: the field diff ({@link LiveFieldDiff}), the column provisioner
 * ({@link ExistingConceptColumnProvisioner}) and the store's column-catalog invalidation on a
 * model swap ({@link JdbcBusinessConceptStore}) -- the three seams an additive field on a running
 * app crosses below the generated REST layer.
 */
class LiveFieldAddH2Test {

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        SqlDialects.setActive(H2Dialect.INSTANCE);
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID NOT NULL, name VARCHAR(255), "
                    + "version BIGINT DEFAULT 0, row_version BIGINT DEFAULT 0, "
                    + "tenant_id VARCHAR(120) NOT NULL DEFAULT 'default', PRIMARY KEY (id))");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        SqlDialects.resetActiveForTesting();
    }

    private static CompiledModel compile(String extraFields) throws Exception {
        String json = "{ \"namespace\": \"live1.test\", \"dslVersion\": \"1.0.0\", \"version\": \"1.0\", \"concepts\": ["
                + "{ \"name\": \"Widget\", \"fields\": ["
                + "{ \"name\": \"id\", \"type\": \"uuid\", \"id\": true, \"required\": true },"
                + "{ \"name\": \"name\", \"type\": \"string\" }" + extraFields + "] } ] }";
        Path path = Files.createTempFile("npdev-live1-", ".json");
        Files.writeString(path, json);
        return new ModelCompiler().compile(new JsonModelParser().parse(path));
    }

    private Set<String> columns(String table) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = LOWER('" + table + "')")) {
            while (rs.next()) {
                out.add(rs.getString(1).toLowerCase());
            }
        }
        return out;
    }

    // ---- LiveFieldDiff ------------------------------------------------------------------------

    @Test
    void anOptionalScalarFieldIsAddedAndNothingIsRefused() throws Exception {
        LiveFieldDiff.Result diff = LiveFieldDiff.compute(compile(""),
                compile(",{ \"name\": \"colour\", \"type\": \"string\" },{ \"name\": \"qty\", \"type\": \"int\" }"));

        assertFalse(diff.refused(), String.valueOf(diff.refusals()));
        assertEquals(2, diff.added().size());
        LiveFieldDiff.AddedField colour = diff.added().get(0);
        assertEquals("Widget.colour", colour.label());
        assertEquals("widgets", colour.table());
        assertEquals("colour", colour.column());
        assertEquals("VARCHAR(255)", colour.sqlType());
        assertEquals("INTEGER", diff.added().get(1).sqlType());
    }

    @Test
    void anUnchangedModelHasNoDiff() throws Exception {
        LiveFieldDiff.Result diff = LiveFieldDiff.compute(compile(""), compile(""));
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.refusals().isEmpty());
    }

    @Test
    void removalRetypeRequiredUniqueReferenceAndJsonAreRefusedNotAdded() throws Exception {
        CompiledModel live = compile(",{ \"name\": \"qty\", \"type\": \"int\" }");

        assertRefused(live, compile(""), "removed or renamed");
        assertRefused(live, compile(",{ \"name\": \"qty\", \"type\": \"long\" }"), "type changed");
        assertRefused(live, compile(",{ \"name\": \"qty\", \"type\": \"int\" },{ \"name\": \"must\", \"type\": \"string\", \"required\": true }"),
                "is required");
        assertRefused(live, compile(",{ \"name\": \"qty\", \"type\": \"int\" },{ \"name\": \"code\", \"type\": \"string\", \"unique\": true }"),
                "is unique");
        assertRefused(live, compile(",{ \"name\": \"qty\", \"type\": \"int\" },{ \"name\": \"meta\", \"type\": \"object\", \"properties\": { \"a\": { \"type\": \"string\" } } }"),
                "JSON-typed");
        assertRefused(live, compile(",{ \"name\": \"qty\", \"type\": \"int\" },{ \"name\": \"tags\", \"type\": \"array\", \"items\": { \"type\": \"object\", \"properties\": { \"a\": { \"type\": \"string\" } } } }"),
                "JSON-typed");
    }

    @Test
    void aColumnNameCollisionBetweenTwoSpellingsIsRefused() throws Exception {
        CompiledModel live = compile(",{ \"name\": \"fooBar\", \"type\": \"string\" }");
        assertRefused(live, compile(",{ \"name\": \"fooBar\", \"type\": \"string\" },{ \"name\": \"foo_bar\", \"type\": \"string\" }"),
                "collides");
    }

    private static void assertRefused(CompiledModel live, CompiledModel candidate, String expectedFragment) {
        LiveFieldDiff.Result diff = LiveFieldDiff.compute(live, candidate);
        assertTrue(diff.refused(), "expected a refusal containing '" + expectedFragment + "'");
        assertTrue(diff.refusals().stream().anyMatch(r -> r.contains(expectedFragment)),
                "expected '" + expectedFragment + "' in " + diff.refusals());
        assertTrue(diff.added().stream().noneMatch(a -> expectedFragment.equals("is required") && a.field().equals("must")),
                "a refused field must never be offered for an ALTER");
    }

    @Test
    void aHostileFieldNameIsSanitisedToAPlainColumnNeverSqlText() {
        CompiledField id = new CompiledField("id", "uuid", "java.util.UUID", true, true, false);
        CompiledField hostile = new CompiledField("x\"); DROP TABLE widgets; --", "string", "String", false, false, false);
        CompiledConcept before = new CompiledConcept("Widget", "Widget", "widgets", List.of(id));
        CompiledConcept after = new CompiledConcept("Widget", "Widget", "widgets", List.of(id, hostile));
        CompiledModel live = new CompiledModel("t", "1.0.0", "1.0.0", Map.of("Widget", before));
        CompiledModel candidate = new CompiledModel("t", "1.0.0", "1.0.0", Map.of("Widget", after));

        LiveFieldDiff.Result diff = LiveFieldDiff.compute(live, candidate);

        assertEquals(1, diff.added().size(), String.valueOf(diff.refusals()));
        String column = diff.added().get(0).column();
        assertTrue(column.matches("[a-z0-9_]+"), "column must be a plain identifier but was: " + column);
        assertFalse(column.contains("drop table widgets;"));
    }

    // ---- ExistingConceptColumnProvisioner -----------------------------------------------------

    @Test
    void provisionerAddsTheColumnIdempotently() throws Exception {
        LiveFieldDiff.AddedField added = new LiveFieldDiff.AddedField("Widget", "colour", "widgets", "colour", "VARCHAR(255)");

        ExistingConceptColumnProvisioner.Result first = ExistingConceptColumnProvisioner.provision(dataSource(), List.of(added));
        ExistingConceptColumnProvisioner.Result second = ExistingConceptColumnProvisioner.provision(dataSource(), List.of(added));

        assertEquals(List.of("Widget.colour"), first.provisioned());
        assertEquals(List.of("Widget.colour"), second.provisioned());
        assertTrue(columns("widgets").contains("colour"));
    }

    @Test
    void provisionerRefusesAnUnsafeIdentifierBeforeAnyStatementRuns() throws Exception {
        List<LiveFieldDiff.AddedField> bad = List.of(
                new LiveFieldDiff.AddedField("Widget", "ok", "widgets", "fine", "VARCHAR(255)"),
                new LiveFieldDiff.AddedField("Widget", "evil", "widgets; DROP TABLE widgets", "x", "VARCHAR(255)"));
        assertThrows(IllegalStateException.class, () -> ExistingConceptColumnProvisioner.provision(dataSource(), bad));

        List<LiveFieldDiff.AddedField> badColumn = List.of(
                new LiveFieldDiff.AddedField("Widget", "evil", "widgets", "x\" VARCHAR(1)); DROP TABLE widgets; --", "VARCHAR(255)"));
        assertThrows(IllegalStateException.class, () -> ExistingConceptColumnProvisioner.provision(dataSource(), badColumn));

        List<LiveFieldDiff.AddedField> badType = List.of(
                new LiveFieldDiff.AddedField("Widget", "evil", "widgets", "ok2", "INT); DROP TABLE widgets; --"));
        assertThrows(IllegalStateException.class, () -> ExistingConceptColumnProvisioner.provision(dataSource(), badType));

        Set<String> columns = columns("widgets");
        assertFalse(columns.contains("fine"), "validation happens before the first ALTER, so no column was added");
        assertFalse(columns.contains("ok2"));
        assertTrue(columns.contains("name"), "the table is intact");
    }

    @Test
    void aFailingAlterThrowsNamingTheFieldAndKeepsEarlierColumnsReported() throws Exception {
        List<LiveFieldDiff.AddedField> added = List.of(
                new LiveFieldDiff.AddedField("Widget", "colour", "widgets", "colour", "VARCHAR(255)"),
                new LiveFieldDiff.AddedField("Ghost", "shade", "no_such_table", "shade", "VARCHAR(255)"));

        ExistingConceptColumnProvisioner.ProvisioningFailedException failure = assertThrows(
                ExistingConceptColumnProvisioner.ProvisioningFailedException.class,
                () -> ExistingConceptColumnProvisioner.provision(dataSource(), added));

        assertEquals("Ghost.shade", failure.failedField());
        assertEquals(List.of("Widget.colour"), failure.alreadyApplied());
        assertTrue(failure.getMessage().contains("no_such_table.shade"), failure.getMessage());
    }

    // ---- JdbcBusinessConceptStore: column catalog invalidated by a swap ----------------------

    @Test
    void theStoreSeesAnAlteredInColumnOnlyAfterTheModelSwap() throws Exception {
        CompiledModel v1 = compile("");
        CompiledModel v2 = compile(",{ \"name\": \"colour\", \"type\": \"string\" }");
        ModelHolder holder = new ModelHolder(v1);
        JdbcBusinessConceptStore store = new JdbcBusinessConceptStore(dataSource(), holder, H2Dialect.INSTANCE);
        UUID id = UUID.randomUUID();
        // Warm the column catalog on the OLD shape -- this is the state a long-running app is in.
        store.save(new ConceptRecord("Widget", id.toString(), "t1", Map.of("name", "first")));
        assertEquals("first", store.findById("t1", "Widget", id.toString()).orElseThrow().data().get("name"));

        ExistingConceptColumnProvisioner.provision(dataSource(), LiveFieldDiff.compute(v1, v2).added());
        holder.swap(v2);

        UUID second = UUID.randomUUID();
        store.save(new ConceptRecord("Widget", second.toString(), "t1", Map.of("name", "second", "colour", "red")));
        ConceptRecord read = store.findById("t1", "Widget", second.toString()).orElseThrow();
        assertEquals("red", read.data().get("colour"), "the new column must be written and read without a restart");
        // A row written before the field existed reads back with the field null, not an error.
        assertNull(store.findById("t1", "Widget", id.toString()).orElseThrow().data().get("colour"));
    }

    @Test
    void withoutTheSwapTheStoreStillServesTheOldShape() throws Exception {
        CompiledModel v1 = compile("");
        ModelHolder holder = new ModelHolder(v1);
        JdbcBusinessConceptStore store = new JdbcBusinessConceptStore(dataSource(), holder, H2Dialect.INSTANCE);
        UUID id = UUID.randomUUID();
        store.save(new ConceptRecord("Widget", id.toString(), "t1", Map.of("name", "first")));

        // The ALTER ran but the swap did not (a refused/failed reload): the old model keeps serving
        // exactly as before, and the extra column is simply invisible to it.
        ExistingConceptColumnProvisioner.provision(dataSource(),
                List.of(new LiveFieldDiff.AddedField("Widget", "colour", "widgets", "colour", "VARCHAR(255)")));

        ConceptRecord read = store.findById("t1", "Widget", id.toString()).orElseThrow();
        assertEquals("first", read.data().get("name"));
        assertFalse(read.data().containsKey("colour"));
    }

    private DataSource dataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url);
            }

            @Override
            public Connection getConnection(String username, String password) {
                throw new UnsupportedOperationException();
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
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }
}
