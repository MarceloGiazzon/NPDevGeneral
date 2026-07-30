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
import com.npdev.kernel.concepts.ConceptReadRequest;
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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Move 4 (docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md): {@code aggregate.onCommit} runs a declared
 * procedure after the tree is written, inside G1's own transaction (REG-72) -- so a side-effect
 * write to a SIBLING concept (the shape M8/M9's syncOcupacao and REG-75 both needed) either lands
 * together with the aggregate or rolls back together with it. Same rigor as
 * AggregateRuntimeCommitTransactionalTest: a real H2-backed JdbcBusinessConceptStore + a real
 * DataSourceTransactionManager, not a mock -- a rollback can only be observed against a real
 * connection actually participating in a real transaction.
 */
class AggregateRuntimeOnCommitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
          "concepts": [
            { "name": "Movimento", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "situacao", "type": "string" },
              { "name": "ledgerRef", "type": "uuid" } ] },
            { "name": "Ledger", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "quantidade", "type": "string" },
              { "name": "synced", "type": "string" } ] }
          ],
          "aggregates": [
            { "name": "Movimento", "root": "Movimento", "onCommit": "SyncLedgerProcedure", "collections": [] }
          ],
          "procedures": [
            { "name": "SyncLedgerProcedure", "steps": [
              { "name": "sync-ledger", "type": "patchConcept", "concept": "Ledger", "id": "$input.ledgerRef",
                "set": { "synced": "true" }, "target": "patched" },
              { "name": "return-patched", "type": "return", "value": "$patched" }
            ] }
          ]
        }
        """;

    private DataSource dataSource;
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
        JdbcBusinessConceptStore store = new JdbcBusinessConceptStore(dataSource, model);
        gateway = new DefaultConceptGateway(store, PermissionEvaluator.allowAll(), TenantIsolationPolicy.STRICT_EQUALS, AuditLogStore.noop());
        transactionManager = new DataSourceTransactionManager(dataSource);

        gateway.save(new ConceptWriteRequest("Ledger", "L1", "trial",
                Map.of("id", "L1", "quantidade", "10", "synced", "false")), ctx);
    }

    private static String createTableSql(CompiledConcept concept) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(concept.getTableName()).append(" (");
        for (CompiledField field : concept.getFields()) {
            String column = toSnakeCase(field.getName());
            sql.append(column).append(" VARCHAR(255)").append(field.isId() ? " PRIMARY KEY" : "").append(", ");
        }
        sql.append("tenant_id VARCHAR(120) NOT NULL)");
        return sql.toString();
    }

    private static String toSnakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    /** Success path: the aggregate's own root commits AND onCommit's patchConcept updates the sibling. */
    @Test
    void onCommitPatchesSiblingConceptAfterRootCommits() {
        ProcedureRunner procedureRunner = new ProcedureRunner(model, gateway, null, null);
        AggregateRuntime runtime = new AggregateRuntime(model, gateway, procedureRunner, transactionManager);

        Map<String, Object> draft = new LinkedHashMap<>(Map.of("id", "M1", "situacao", "Concluido", "ledgerRef", "L1"));
        runtime.commit("Movimento", draft, ctx);

        assertEquals("Concluido", gateway.read(new ConceptReadRequest("Movimento", "M1", null), ctx).get().data().get("situacao"),
                "the aggregate's own root must be committed");
        assertEquals("true", gateway.read(new ConceptReadRequest("Ledger", "L1", null), ctx).get().data().get("synced"),
                "onCommit's patchConcept must have updated the sibling Ledger record");
        assertEquals("10", gateway.read(new ConceptReadRequest("Ledger", "L1", null), ctx).get().data().get("quantidade"),
                "patchConcept must preserve fields it does not name in set");
    }

    /**
     * Failure path: onCommit's procedure fails (patchConcept targets a Ledger id that does not
     * exist) -- the WHOLE commit, including the aggregate's own root write that already landed
     * inside this same call, must roll back. This is only correct because G1 (REG-72) wraps
     * commitInternal in a real transaction; onCommit intentionally throws (rather than returning a
     * failure) so the exception propagates out through that boundary.
     */
    @Test
    void onCommitFailureRollsBackTheAggregatesOwnRootWriteToo() throws Exception {
        // Delete the Ledger row the procedure targets so patchConcept's readConcept fails.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM " + model.getConcepts().stream()
                    .filter(c -> c.getName().equals("Ledger")).findFirst().orElseThrow().getTableName());
        }

        ProcedureRunner procedureRunner = new ProcedureRunner(model, gateway, null, null);
        AggregateRuntime runtime = new AggregateRuntime(model, gateway, procedureRunner, transactionManager);

        Map<String, Object> draft = new LinkedHashMap<>(Map.of("id", "M1", "situacao", "Concluido", "ledgerRef", "L1"));
        assertThrows(RuntimeException.class, () -> runtime.commit("Movimento", draft, ctx));

        assertEquals(java.util.Optional.empty(), gateway.read(new ConceptReadRequest("Movimento", "M1", null), ctx),
                "the root write must have rolled back too -- onCommit failing must undo the whole commit");
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
