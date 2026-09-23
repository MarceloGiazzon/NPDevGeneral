package com.finalexec.db;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
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
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-244 Phase 4B, end to end against a real H2 database: {@link NewConceptSchemaProvisioner}
 * creates a table for a brand-new, bond-free concept, refuses (by name, with a reason) a concept it
 * cannot yet handle, and never touches an existing concept's table.
 */
class NewConceptSchemaProvisionerTest {

    private String url;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        // SqlDialects.active() defaults to Postgres and memoizes the first resolution for the whole
        // JVM (see its own javadoc) -- MissingTableCreationPass has no other test in this module
        // pinning H2, so without this, its dialect-bound SQL is built for the wrong engine.
        SqlDialects.setActive(H2Dialect.INSTANCE);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        SqlDialects.resetActiveForTesting();
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

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-new-concept-provisioner-", ".json");
        Files.writeString(modelPath, json);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new ModelCompiler().compile(ast);
    }

    // The canonical model schema requires at least one concept, so "no concepts relevant to this
    // test" is expressed as one unrelated placeholder rather than an empty array.
    private static final String EMPTY_MODEL = """
            { "namespace": "reg244.phase4b", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
              { "name": "Placeholder", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true }
              ] }
            ] }
            """;

    private Set<String> columnNames(String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        // H2's system catalog is fixed-case INFORMATION_SCHEMA -- an unquoted lowercase reference
        // does not resolve under this class's own DATABASE_TO_UPPER=false connection (see the
        // matching fix in H2Dialect.tableExistsInCurrentSchemaSql).
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = LOWER('" + table + "')")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    @Test
    void newBondFreeConceptGetsTableCreatedWithExpectedColumns() throws Exception {
        CompiledModel oldModel = compile(EMPTY_MODEL);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4b", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "label", "type": "string", "required": true },
                    { "name": "quantity", "type": "int" }
                  ] }
                ] }
                """);

        NewConceptSchemaProvisioner.Result result = NewConceptSchemaProvisioner.provision(dataSource(), oldModel, newModel);

        assertEquals(java.util.List.of("Widget"), result.provisioned());
        assertTrue(result.failed().isEmpty(), "expected no failures, got " + result.failed());

        Set<String> columns = columnNames("widgets");
        assertTrue(columns.containsAll(Set.of("id", "label", "quantity", "version", "row_version", "tenant_id")),
                "expected platform + model columns, got " + columns);
    }

    @Test
    void provisioningIsIdempotentOnSecondCall() throws Exception {
        CompiledModel oldModel = compile(EMPTY_MODEL);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4b", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "label", "type": "string" }
                  ] }
                ] }
                """);
        DataSource dataSource = dataSource();

        NewConceptSchemaProvisioner.Result first = NewConceptSchemaProvisioner.provision(dataSource, oldModel, newModel);
        NewConceptSchemaProvisioner.Result second = NewConceptSchemaProvisioner.provision(dataSource, oldModel, newModel);

        assertEquals(java.util.List.of("Widget"), first.provisioned());
        assertEquals(java.util.List.of("Widget"), second.provisioned());
        assertTrue(second.failed().isEmpty());
    }

    @Test
    void bondFieldConceptIsRefusedNotAttempted() throws Exception {
        CompiledModel oldModel = compile("""
                { "namespace": "reg244.phase4b", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] }
                ] }
                """);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4b", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] },
                  { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "widgetId", "type": "reference", "reference": { "target": "Widget" } }
                  ] }
                ] }
                """);

        NewConceptSchemaProvisioner.Result result = NewConceptSchemaProvisioner.provision(dataSource(), oldModel, newModel);

        assertTrue(result.provisioned().isEmpty(), "expected no tables provisioned, got " + result.provisioned());
        assertTrue(result.failed().containsKey("Order"));
        assertTrue(result.failed().get("Order").contains("bond"), "reason should name the bond: " + result.failed());
        assertTrue(columnNames("orders").isEmpty(), "no table should have been created for a refused concept");
    }

    @Test
    void satelliteAndTemporalConceptsAreRefusedWithDistinctReasons() throws Exception {
        CompiledModel oldModel = compile(EMPTY_MODEL);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4b", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] },
                  { "name": "WidgetDetail", "satelliteOf": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] },
                  { "name": "AuditedThing", "temporal": true, "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] }
                ] }
                """);

        NewConceptSchemaProvisioner.Result result = NewConceptSchemaProvisioner.provision(dataSource(), oldModel, newModel);

        assertEquals(java.util.List.of("Widget"), result.provisioned());
        assertTrue(result.failed().get("WidgetDetail").contains("satellite"), "" + result.failed());
        assertTrue(result.failed().get("AuditedThing").contains("temporal"), "" + result.failed());
    }

    @Test
    void existingConceptStructuralChangeIsNotPartOfTheNewConceptDiff() throws Exception {
        CompiledModel oldModel = compile("""
                { "namespace": "reg244.phase4b", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] }
                ] }
                """);
        CompiledModel newModel = compile("""
                { "namespace": "reg244.phase4b", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "label", "type": "string" }
                  ] }
                ] }
                """);

        NewConceptSchemaProvisioner.Result result = NewConceptSchemaProvisioner.provision(dataSource(), oldModel, newModel);

        assertFalse(result.provisioned().contains("Widget"));
        assertFalse(result.failed().containsKey("Widget"));
        assertTrue(result.provisioned().isEmpty());
        assertTrue(result.failed().isEmpty());
    }
}
