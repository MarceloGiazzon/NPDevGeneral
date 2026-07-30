package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.PermissionAwareUiMetadataService;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGatewayOperation;
import com.npdev.kernel.concepts.ConceptGatewaySemanticPolicy;
import com.npdev.kernel.concepts.ConceptGatewayTraceRecord;
import com.npdev.kernel.concepts.ConceptGatewayTraceSink;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5): a panelAction with {@code binding:
 * "conceptQuery"} -- PanelRuntime.executeAction's own conceptquery branch lists every record of a
 * concept through the SAME governed ConceptGateway every other binding uses. Zero declarations in
 * any real/fixture model AND zero prior test coverage at any level before this (unlike most other
 * Wave 5 items, which had solid hidden unit-test coverage already).
 */
class PanelRuntimeConceptQueryActionTest {

    @Test
    void executesConceptQueryPanelActionAgainstTheGovernedGateway() {
        RuntimeMetadataService metadataService = new RuntimeMetadataService(new ObjectMapper());
        CollectingTraceSink traceSink = new CollectingTraceSink();
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                ConceptGatewaySemanticPolicy.noop(),
                traceSink
        );
        gateway.save(new ConceptWriteRequest("Contact", "c-1", "dev", Map.of("id", "c-1", "message", "hello")), ExecutionContext.of("dev", "operator"));
        gateway.save(new ConceptWriteRequest("Contact", "c-2", "dev", Map.of("id", "c-2", "message", "world")), ExecutionContext.of("dev", "operator"));

        PanelRuntime runtime = new PanelRuntime(
                metadataService,
                new PermissionAwareUiMetadataService(
                        metadataService,
                        new ObjectMapper(),
                        PermissionEvaluator.allowAll(),
                        new com.finalexec.npdev.service.BetaSecurityRoleEvaluator()
                ),
                conceptQueryPanelModel(),
                gateway,
                null,
                null
        );

        Map<String, Object> result = runtime.executeAction(
                "ContactPanel",
                "listContacts",
                Map.of(),
                ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR"))
        );

        assertEquals("OK", result.get("status"));
        assertEquals("conceptQuery", result.get("binding"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("result");
        assertEquals(2, records.size(), "conceptQuery must list every record of the concept, unfiltered");
        assertTrue(records.stream().anyMatch(r -> "Contact".equals(r.get("concept"))));
        assertTrue(records.stream().anyMatch(r -> "hello".equals(((Map<?, ?>) r.get("data")).get("message"))));
        assertTrue(records.stream().anyMatch(r -> "world".equals(((Map<?, ?>) r.get("data")).get("message"))));

        @SuppressWarnings("unchecked")
        List<ConceptGatewayTraceRecord> trace = (List<ConceptGatewayTraceRecord>) result.get("gatewayTrace");
        assertEquals(1, trace.size(), "conceptQuery must go through the governed ConceptGateway, not a side channel");
        assertEquals(ConceptGatewayOperation.LIST, trace.get(0).operation());
    }

    private static CompiledModel conceptQueryPanelModel() {
        CompiledPanel panel = new CompiledPanel(
                "ContactPanel",
                "/contacts",
                "Contacts",
                List.of(new CompiledPanelDataSource("contacts", "Contact", null, null, Map.of(), null, null, null)),
                new CompiledPanelLayout("table", List.of(), List.of("message"), Map.of()),
                List.of(),
                null,
                null,
                List.of(new CompiledPanelAction(
                        "listContacts",
                        "List contacts",
                        "conceptQuery",
                        "Contact",
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null
                )),
                Map.of(),
                Map.of(),
                null
        );
        return new CompiledModel(
                "panel.runtime.conceptquery",
                "1.0.0",
                "1.0.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(panel)
        );
    }

    private static final class CollectingTraceSink implements ConceptGatewayTraceSink {
        private final List<ConceptGatewayTraceRecord> records = new ArrayList<>();

        @Override
        public void append(ConceptGatewayTraceRecord record) {
            records.add(record);
        }

        @Override
        public List<ConceptGatewayTraceRecord> records() {
            return List.copyOf(records);
        }
    }
}
