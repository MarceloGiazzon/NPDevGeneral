package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.security.PermissionGrant;
import com.npdev.kernel.security.StaticPermissionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-301: a panel dataSource naming a query is gated by that query's permissionRequirements[]
 * exactly as GET /api/queries/{name} is -- the panel is not a side door to a guarded query's rows.
 */
class PanelRuntimeQueryPermissionTest {

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "t", "version": "1.0",
          "concepts": [
            { "name": "Ledger", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "amount", "type": "int" } ] }
          ],
          "queries": [
            { "name": "AuditedLedger", "concept": "Ledger", "permissionRequirements": [ "ledger.audit" ] }
          ],
          "panels": [
            { "name": "LedgerPanel", "route": "/ledger",
              "dataSources": [ { "name": "rows", "query": "AuditedLedger" } ],
              "layout": { "type": "table" } }
          ]
        }
        """;

    private static ExecutionContext caller(String role) {
        return ExecutionContext.of("dev", "tester").withRoles(Set.of(role));
    }

    private static PanelRuntime runtime() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(model), model);
        gateway.save(new ConceptWriteRequest("Ledger", "R1", "dev", Map.of("id", "R1", "amount", 5)), caller("auditor"));
        return new PanelRuntime(
                new RuntimeMetadataService(new ObjectMapper()),
                null,
                new ModelHolder(model),
                gateway,
                null,
                null,
                null,
                null,
                new StaticPermissionEvaluator(List.of(new PermissionGrant("ledger.audit", "", "", "auditor"))));
    }

    @Test
    void aCallerHoldingTheRequirementLoadsTheRows() throws Exception {
        Map<String, Object> loaded = runtime().loadPanel("LedgerPanel", Map.of(), caller("auditor"));
        assertTrue(String.valueOf(loaded).contains("R1"), String.valueOf(loaded));
    }

    @Test
    void aCallerLackingTheRequirementIsRefused() throws Exception {
        PanelRuntime runtime = runtime();
        ConceptGatewayAccessDeniedException denied = assertThrows(ConceptGatewayAccessDeniedException.class,
                () -> runtime.loadPanel("LedgerPanel", Map.of(), caller("clerk")));
        assertEquals("PERMISSION_DENIED", denied.code());
        assertTrue(denied.getMessage().contains("ledger.audit"), denied.getMessage());
    }
}
