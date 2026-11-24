package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.ProcedureRunner;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptQueryPredicateCompiler;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-357: {@code panelDataSource.params} binds the named query's declared {@code parameters[]}. Before this the
 * panel runtime compiled the query's {@code where} with no parameters at all, so a panel reading a query that uses
 * {@code $params.x} answered 500 ("The server had a problem") and the keyword was declared and carried but never
 * acted on. Found by running the browser scenarios against dsl-conformance-max, whose review panel does exactly that.
 */
class PanelRuntimeQueryParamsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = """
        {
          "dslVersion": "3.0.0", "namespace": "wms.panelparams", "version": "1.0",
          "concepts": [
            { "name": "Widget", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "owner", "type": "string" } ] }
          ],
          "queries": [
            { "name": "WidgetsOwnedBy", "concept": "Widget", "where": "owner == $params.owner",
              "parameters": [ { "name": "owner", "type": "string", "required": true } ] },
            { "name": "WidgetsAnyOwner", "concept": "Widget",
              "parameters": [ { "name": "unused", "type": "string" } ] }
          ],
          "screens": [
            { "name": "AlicePanel", "kind": "custom",
              "panel": { "route": "/alice",
                "dataSources": [ { "name": "mine", "query": "WidgetsOwnedBy", "params": { "owner": "alice" } } ] } },
            { "name": "UnboundPanel", "kind": "custom",
              "panel": { "route": "/unbound",
                "dataSources": [ { "name": "nobody", "query": "WidgetsOwnedBy" } ] } },
            { "name": "AllPanel", "kind": "custom",
              "panel": { "route": "/all",
                "dataSources": [ { "name": "everyone", "query": "WidgetsAnyOwner" } ] } }
          ]
        }
        """;

    private static final class NoProcedures extends ProcedureRunner {
        NoProcedures() {
            super((CompiledModel) null, (ConceptGateway) null);
        }

        @Override
        public ProcedureExecutionResult execute(String procedureName, Map<String, Object> input, ExecutionContext context) {
            return ProcedureExecutionResult.success(Map.of(), List.of());
        }
    }

    private static PanelRuntime runtime() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        CompiledModel model = new ModelCompiler().compile(ast);
        DefaultConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        ExecutionContext ctx = ExecutionContext.of("trial", "tester");
        gateway.save(new ConceptWriteRequest("Widget", "w1", "trial", Map.of("id", "w1", "owner", "alice")), ctx);
        gateway.save(new ConceptWriteRequest("Widget", "w2", "trial", Map.of("id", "w2", "owner", "bob")), ctx);
        return new PanelRuntime(new RuntimeMetadataService(MAPPER), null, model, gateway, new NoProcedures());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> loaded, String dataSource) {
        return (List<Map<String, Object>>) ((Map<String, Object>) loaded.get("data")).get(dataSource);
    }

    @Test
    void declaredParamsFilterTheNamedQuery() throws Exception {
        Map<String, Object> loaded = runtime().loadPanel("AlicePanel", Map.of(), ExecutionContext.of("trial", "tester"));

        List<Map<String, Object>> mine = rows(loaded, "mine");
        assertEquals(1, mine.size(), "only alice's widget matches owner == $params.owner");
        assertEquals("w1", mine.get(0).get("id"));
    }

    @Test
    void aQueryWhoseWhereUsesAParameterTheDataSourceDoesNotSupplyIsRefusedByName() throws Exception {
        PanelRuntime runtime = runtime();

        ConceptQueryPredicateCompiler.UnsupportedPredicateException refused = assertThrows(
                ConceptQueryPredicateCompiler.UnsupportedPredicateException.class,
                () -> runtime.loadPanel("UnboundPanel", Map.of(), ExecutionContext.of("trial", "tester")));
        assertTrue(refused.getMessage().contains("$params.owner"), refused.getMessage());
        assertTrue(refused.getMessage().contains("not supplied"), "refused, never silently unfiltered: " + refused.getMessage());
    }

    @Test
    void aDeclaredParameterTheWhereNeverUsesNeedsNoValue() throws Exception {
        Map<String, Object> loaded = runtime().loadPanel("AllPanel", Map.of(), ExecutionContext.of("trial", "tester"));

        assertEquals(2, rows(loaded, "everyone").size(),
                "an optional parameter the panel never fills must not break a query that does not use it");
    }
}
