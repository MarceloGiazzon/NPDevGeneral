package com.finalexec.db;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListSlice;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.DefaultConceptGateway;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-244 Phase 4C, end to end against a real H2 database: a concept that {@link
 * NewConceptSchemaProvisioner} provisioned a table for (the same "brand-new, bond-free" scope
 * REG-245/Phase 4B already ships) becomes fully CRUD-reachable through {@link
 * LiveConceptCrudSupport} with no generated app, mirroring what the assembled
 * {@code GeneratedConceptCrudController}'s live-concept fallback does at runtime.
 */
class LiveConceptCrudSupportTest {

    private String url;
    private DataSource dataSource;
    private static final ExecutionContext CONTEXT = ExecutionContext.of("tenant-a", "test-actor");

    private static final String EMPTY_MODEL = """
            { "namespace": "reg244.phase4c", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
              { "name": "Placeholder", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true }
              ] }
            ] }
            """;

    private static final String MODEL_WITH_WIDGET = """
            { "namespace": "reg244.phase4c", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
              { "name": "Placeholder", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true }
              ] },
              { "name": "Widget", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "label", "type": "string", "required": true },
                { "name": "quantity", "type": "int" }
              ] }
            ] }
            """;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        SqlDialects.setActive(H2Dialect.INSTANCE);
        dataSource = new DataSource() {
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

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        SqlDialects.resetActiveForTesting();
    }

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-live-concept-crud-", ".json");
        Files.writeString(modelPath, json);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new ModelCompiler().compile(ast);
    }

    @Test
    void resolveIsEmptyForAConceptNameNotInTheLiveModel() throws Exception {
        CompiledModel model = compile(EMPTY_MODEL);
        ConceptGateway gateway = new DefaultConceptGateway(new JdbcBusinessConceptStore(dataSource, model));

        Optional<LiveConceptCrudSupport.LiveConceptOps> ops =
                LiveConceptCrudSupport.resolve(model, "widgets", gateway, CONTEXT);

        assertTrue(ops.isEmpty());
    }

    @Test
    void resolveIsEmptyForABondFieldConceptEvenIfNamedCorrectly() throws Exception {
        CompiledModel model = compile("""
                { "namespace": "reg244.phase4c", "dslVersion": "1.0.0", "version": "1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] },
                  { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "widgetId", "type": "reference", "reference": { "target": "Widget" } }
                  ] }
                ] }
                """);
        ConceptGateway gateway = new DefaultConceptGateway(new JdbcBusinessConceptStore(dataSource, model));

        Optional<LiveConceptCrudSupport.LiveConceptOps> ops =
                LiveConceptCrudSupport.resolve(model, "orders", gateway, CONTEXT);

        assertTrue(ops.isEmpty(), "a bond-field concept must be refused, same scope as NewConceptSchemaProvisioner");
    }

    @Test
    void newlyProvisionedConceptIsFullyCrudReachableThroughLiveOps() throws Exception {
        CompiledModel oldModel = compile(EMPTY_MODEL);
        CompiledModel newModel = compile(MODEL_WITH_WIDGET);

        // Mirrors the real /model-reload flow: REG-245's provisioner creates the table first, THEN
        // this concept becomes CRUD-reachable -- exactly the two-step sequence MetadataHotSwapController
        // runs, just without the HTTP layer.
        NewConceptSchemaProvisioner.Result provisioned = NewConceptSchemaProvisioner.provision(dataSource, oldModel, newModel);
        assertEquals(java.util.List.of("Widget"), provisioned.provisioned());

        ConceptGateway gateway = new DefaultConceptGateway(new JdbcBusinessConceptStore(dataSource, newModel));
        Optional<LiveConceptCrudSupport.LiveConceptOps> resolved =
                LiveConceptCrudSupport.resolve(newModel, "widgets", gateway, CONTEXT);
        assertTrue(resolved.isPresent());
        LiveConceptCrudSupport.LiveConceptOps ops = resolved.get();
        assertEquals("Widget", ops.conceptName());
        assertEquals("widgets", ops.route());
        assertEquals("id", ops.idField());

        ConceptRecord created = ops.create().apply(Map.of("label", "First Widget", "quantity", 3));
        UUID id = UUID.fromString(created.id());
        assertEquals("First Widget", created.data().get("label"));
        // The generated controller's own response is built from data(), via metadata.fields() --
        // never from record.id() directly -- so the id must be folded into data() too, not just
        // carried on the record's own top-level id() accessor.
        assertEquals(created.id(), created.data().get("id"));

        Optional<ConceptRecord> fetched = ops.getById().apply(id);
        assertTrue(fetched.isPresent());
        assertEquals(3, fetched.get().data().get("quantity"));

        // Partial merge: only "quantity" is supplied, "label" must survive unchanged -- the exact
        // contract the generated controller's batch setField endpoint depends on.
        Optional<ConceptRecord> updated = ops.update().apply(id, Map.of("quantity", 9));
        assertTrue(updated.isPresent());
        assertEquals("First Widget", updated.get().data().get("label"), "partial update must not wipe other fields");
        assertEquals(9, updated.get().data().get("quantity"));

        ConceptListSlice<ConceptRecord> slice = ops.list().get();
        assertEquals(1, slice.records().size());

        ConceptPage page = ops.page().apply(ConceptQuery.firstPage());
        assertEquals(1, page.total());

        boolean deleted = ops.delete().test(id);
        assertTrue(deleted);
        assertTrue(ops.getById().apply(id).isEmpty(), "record must be gone after delete");
        assertFalse(ops.delete().test(id), "deleting an already-deleted id must report not-found, not throw");
    }
}
