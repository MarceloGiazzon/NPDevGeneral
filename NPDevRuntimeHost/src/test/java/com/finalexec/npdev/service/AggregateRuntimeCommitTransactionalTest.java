package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.db.JdbcBusinessConceptStore;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayTraceRecord;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G1 (docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md, REG-72): {@link AggregateRuntime#commit} performs its
 * root upsert, every recursive child upsert, and every reconcile-delete as independent auto-commits
 * -- a failure partway through leaves a half-written aggregate, and because reconcile actively
 * *deletes* children absent from the draft, a failure after a delete has already landed does not
 * restore what was deleted. This is a REAL, depth-2 (Expedicao -> ExpedicaoItem -> MovtoOrigem)
 * scenario against a REAL {@link JdbcBusinessConceptStore} (H2, real transaction manager) -- not a
 * mocked gateway or a noop semantic policy, per the standing rule this repo learned the hard way
 * (feedback_red_proof_must_match_production_shape, then REG-71 a second time): a rollback can only
 * be observed against a real connection actually participating in a real transaction.
 */
class AggregateRuntimeCommitTransactionalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
          "concepts": [
            { "name": "Expedicao", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "cliente", "type": "string" } ] },
            { "name": "ExpedicaoItem", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "expedicaoId", "type": "uuid" }, { "name": "produtoId", "type": "string" } ] },
            { "name": "MovtoOrigem", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "itemSeq", "type": "uuid" }, { "name": "local", "type": "string" } ] }
          ],
          "aggregates": [
            { "name": "Expedicao", "root": "Expedicao",
              "collections": [
                { "name": "itens", "concept": "ExpedicaoItem", "childField": "expedicaoId", "ownership": "owned",
                  "collections": [
                    { "name": "origens", "concept": "MovtoOrigem", "childField": "itemSeq", "ownership": "owned" }
                  ] }
              ] }
          ]
        }
        """;

    private DataSource dataSource;
    private JdbcBusinessConceptStore store;
    private ConceptGateway gateway;
    private DataSourceTransactionManager transactionManager;
    private CompiledModel model;
    private final ExecutionContext ctx = ExecutionContext.of("trial", "tester");

    @BeforeEach
    void setUp() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        model = new ModelCompiler().compile(ast);

        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (CompiledConcept concept : model.getConcepts()) {
                statement.execute(createTableSql(concept));
            }
        }
        store = new JdbcBusinessConceptStore(dataSource, model);
        gateway = new DefaultConceptGateway(store, PermissionEvaluator.allowAll(), TenantIsolationPolicy.STRICT_EQUALS, AuditLogStore.noop());
        transactionManager = new DataSourceTransactionManager(dataSource);

        // Pre-existing state: E1 "Old", item I1 with two origens (kept KO, stale SO).
        gateway.save(new ConceptWriteRequest("Expedicao", "E1", "trial", Map.of("id", "E1", "cliente", "Old")), ctx);
        gateway.save(new ConceptWriteRequest("ExpedicaoItem", "I1", "trial",
                Map.of("id", "I1", "expedicaoId", "E1", "produtoId", "OldP")), ctx);
        gateway.save(new ConceptWriteRequest("MovtoOrigem", "KO", "trial",
                Map.of("id", "KO", "itemSeq", "I1", "local", "KeptOrigin")), ctx);
        gateway.save(new ConceptWriteRequest("MovtoOrigem", "SO", "trial",
                Map.of("id", "SO", "itemSeq", "I1", "local", "StaleOrigin")), ctx);
    }

    private static String createTableSql(CompiledConcept concept) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(concept.getTableName()).append(" (");
        for (CompiledField field : concept.getFields()) {
            String column = toSnakeCase(field.getName());
            String type = "uuid".equals(field.getDslType()) ? "VARCHAR(64)" : "VARCHAR(255)";
            sql.append(column).append(" ").append(type).append(field.isId() ? " PRIMARY KEY" : "").append(", ");
        }
        sql.append("tenant_id VARCHAR(120) NOT NULL)");
        return sql.toString();
    }

    private static String toSnakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * RED before the transaction boundary exists / is wired: this reproduces the exact data-loss
     * shape the plan calls out. Draft: item I1 kept with only its "KeptOrigin" origem (so origem
     * "SO" gets reconcile-deleted -- and that delete completes, because it happens before the next
     * item is processed), then a second item I2 whose save throws. Today (no transaction manager
     * passed to AggregateRuntime), the root rename and the origem delete both already landed by the
     * time the exception propagates -- proving the commit is not atomic.
     */
    @Test
    void nonAtomicCommitLeavesPartialWritesWhenNoTransactionManagerIsWired() {
        ConceptGateway failingGateway = new FailingOnSaveGateway(gateway, "ExpedicaoItem", "I2");
        AggregateRuntime runtime = new AggregateRuntime(model, failingGateway, null, null); // no tx manager

        Map<String, Object> draft = draftWithFailingSecondItem();
        assertThrows(RuntimeException.class, () -> runtime.commit("Expedicao", draft, ctx));

        // Proves the bug: the root rename and the origem reconcile-delete both landed anyway.
        assertEquals("New", gateway.read(new ConceptReadRequest("Expedicao", "E1", null), ctx).get().data().get("cliente"),
                "documents today's non-atomic behavior: root WAS renamed despite the overall commit failing");
        assertTrue(gateway.read(new ConceptReadRequest("MovtoOrigem", "SO", null), ctx).isEmpty(),
                "documents today's non-atomic behavior: the reconciled-away origem WAS deleted despite the overall commit failing");
    }

    /**
     * GREEN after the fix: with a real transaction manager wired, the same failing draft leaves
     * every prior write in this call rolled back -- the root, and the origem the reconcile would
     * have deleted, are both exactly as they were before the failed commit attempt.
     */
    @Test
    void atomicCommitRollsBackEveryPriorWriteWhenTransactionManagerIsWired() {
        ConceptGateway failingGateway = new FailingOnSaveGateway(gateway, "ExpedicaoItem", "I2");
        AggregateRuntime runtime = new AggregateRuntime(model, failingGateway, null, transactionManager);

        Map<String, Object> draft = draftWithFailingSecondItem();
        assertThrows(RuntimeException.class, () -> runtime.commit("Expedicao", draft, ctx));

        assertEquals("Old", gateway.read(new ConceptReadRequest("Expedicao", "E1", null), ctx).get().data().get("cliente"),
                "root must be unchanged -- the whole commit rolled back");
        assertTrue(gateway.read(new ConceptReadRequest("MovtoOrigem", "SO", null), ctx).isPresent(),
                "the origem the reconcile would have deleted must still be present -- its delete rolled back too");
        assertEquals("OldP", gateway.read(new ConceptReadRequest("ExpedicaoItem", "I1", null), ctx).get().data().get("produtoId"),
                "I1 itself must be unchanged");
        assertTrue(gateway.read(new ConceptReadRequest("ExpedicaoItem", "I2", null), ctx).isEmpty(),
                "the never-completed I2 must not exist");
    }

    private Map<String, Object> draftWithFailingSecondItem() {
        Map<String, Object> origemKept = new LinkedHashMap<>(Map.of("id", "KO", "local", "KeptOrigin"));
        Map<String, Object> item1 = new LinkedHashMap<>(Map.of(
                "id", "I1", "produtoId", "OldP", "origens", List.of(origemKept)));
        Map<String, Object> item2Fails = new LinkedHashMap<>(Map.of("id", "I2", "produtoId", "WillFail", "origens", List.of()));
        Map<String, Object> draft = new LinkedHashMap<>(Map.of(
                "id", "E1", "cliente", "New", "itens", List.of(item1, item2Fails)));
        return draft;
    }

    /** Delegates everything to a real gateway, except throws when saving one specific (concept, id). */
    private static final class FailingOnSaveGateway implements ConceptGateway {
        private final ConceptGateway delegate;
        private final String failConcept;
        private final String failId;

        FailingOnSaveGateway(ConceptGateway delegate, String failConcept, String failId) {
            this.delegate = delegate;
            this.failConcept = failConcept;
            this.failId = failId;
        }

        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            return delegate.read(request, context);
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            return delegate.list(request, context);
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            if (failConcept.equalsIgnoreCase(request.conceptName()) && failId.equals(request.id())) {
                throw new IllegalStateException("Injected failure saving " + failConcept + " " + failId);
            }
            return delegate.save(request, context);
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            delegate.delete(request, context);
        }

        @Override
        public List<ConceptGatewayTraceRecord> explain() {
            return delegate.explain();
        }
    }

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
