package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.BetaSecurityRoleEvaluator;
import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.PermissionAwareUiMetadataService;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.dsl.v1.compiled.CompiledProcedureStep;
import com.npdev.dsl.v1.compiled.CompiledQuery;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelRuntimeTest {
    private final RuntimeMetadataService metadataService = new RuntimeMetadataService(new ObjectMapper());
    private final PanelRuntime panelRuntime = new PanelRuntime(
            metadataService,
            new PermissionAwareUiMetadataService(
                    metadataService,
                    new ObjectMapper(),
                    PermissionEvaluator.allowAll(),
                    new BetaSecurityRoleEvaluator()
            )
    );

    @Test
    void rendersPermissionAwarePanelViewModelFromRuntimeMetadata() {
        Map<String, Object> panel = panelRuntime.renderConceptPanel(
                "Appointment",
                ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR"))
        );

        assertEquals("panel-runtime", panel.get("surfaceType"));
        assertEquals("AppointmentPanel", panel.get("panelName"));
        assertEquals("ConceptGateway", panel.get("governedDataAccess"));
        assertEquals("dev", panel.get("tenantId"));
        assertEquals(Boolean.TRUE, panel.get("permissionAware"));

        @SuppressWarnings("unchecked")
        Map<String, Object> concept = (Map<String, Object>) panel.get("concept");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) panel.get("fields");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) panel.get("actions");
        @SuppressWarnings("unchecked")
        Map<String, Object> layout = (Map<String, Object>) panel.get("layout");

        assertEquals("Appointment", concept.get("name"));
        assertTrue(fields.stream().anyMatch(field -> "status".equals(field.get("fieldPath"))));
        assertTrue(actions.stream().anyMatch(action -> "CreateAppointment".equals(action.get("name"))));
        assertTrue(((List<?>) layout.get("tabs")).contains("Overview"));
    }

    @Test
    void rejectsBlankConceptName() {
        assertThrows(IllegalArgumentException.class,
                () -> panelRuntime.renderConceptPanel(" ", ExecutionContext.anonymous()));
    }

    @Test
    void executesPanelProcedureActionThroughConceptGateway() {
        CollectingTraceSink traceSink = new CollectingTraceSink();
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                ConceptGatewaySemanticPolicy.noop(),
                traceSink
        );
        PanelRuntime runtime = new PanelRuntime(
                metadataService,
                null,
                executablePanelModel(),
                gateway,
                null,
                null
        );

        Map<String, Object> result = runtime.executeAction(
                "ContactPanel",
                "submit",
                Map.of(
                        "id", "contact-1",
                        "data", Map.of("message", "hello")
                ),
                ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR"))
        );
        @SuppressWarnings("unchecked")
        List<ConceptGatewayTraceRecord> actionTrace = (List<ConceptGatewayTraceRecord>) result.get("gatewayTrace");

        assertEquals("OK", result.get("status"));
        assertEquals(1, actionTrace.size());
        assertEquals(ConceptGatewayOperation.SAVE, actionTrace.get(0).operation());
        assertTrue(actionTrace.get(0).ruleProfiles().contains("interactive"));
        assertTrue(actionTrace.get(0).ruleProfiles().contains("beforeCommit"));
        assertTrue(actionTrace.get(0).ruleProfiles().contains("afterCommit"));
        assertTrue(traceSink.records().stream()
                .filter(record -> "Contact".equals(record.conceptName()))
                .findAny()
                .isPresent());

        Map<String, Object> loaded = runtime.loadPanel(
                "ContactPanel",
                Map.of(),
                ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR"))
        );
        @SuppressWarnings("unchecked")
        List<ConceptGatewayTraceRecord> loadTrace = (List<ConceptGatewayTraceRecord>) loaded.get("gatewayTrace");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loaded.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contacts = (List<Map<String, Object>>) data.get("contacts");

        assertEquals(2, traceSink.records().size());
        assertEquals(1, loadTrace.size());
        assertEquals(ConceptGatewayOperation.LIST, loadTrace.get(0).operation());
        assertTrue(loadTrace.get(0).ruleProfiles().contains("interactive"));
        assertTrue(loadTrace.get(0).ruleProfiles().contains("query"));
        assertEquals(1, contacts.size());
        assertEquals("Contact", contacts.get(0).get("concept"));
        assertEquals("ConceptGateway", loaded.get("governedDataAccess"));
    }

    @Test
    void rendersFallbackUiWhenCustomPanelDataSourceCannotHydrate() {
        PanelRuntime runtime = new PanelRuntime(
                metadataService,
                null,
                executablePanelModel(),
                null,
                null,
                null
        );

        Map<String, Object> loaded = runtime.loadPanel(
                "ContactPanel",
                Map.of(),
                ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR"))
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataSources = (List<Map<String, Object>>) loaded.get("dataSources");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loaded.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> contacts = (Map<String, Object>) data.get("contacts");

        assertEquals(true, loaded.get("fallbackUi"));
        assertEquals(true, dataSources.get(0).get("fallback"));
        assertEquals("CONCEPT_GATEWAY_UNAVAILABLE", dataSources.get(0).get("fallbackCode"));
        assertEquals("UNAVAILABLE", contacts.get("status"));
        assertEquals("CONCEPT_GATEWAY_UNAVAILABLE", contacts.get("code"));
    }

    @Test
    void loadPanelNestsChildDataSourceUnderItsDeclaredParentRows() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                ConceptGatewaySemanticPolicy.noop(),
                new CollectingTraceSink()
        );
        ExecutionContext context = ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR"));

        gateway.save(new ConceptWriteRequest("Movimento", "mov-1", null, Map.of("tipo", "Recebimento")), context);
        gateway.save(new ConceptWriteRequest("Movimento", "mov-2", null, Map.of("tipo", "Expedicao")), context);
        gateway.save(new ConceptWriteRequest("MovimentoItem", "item-1a", null,
                Map.of("movimentoId", "mov-1", "quantidade", 10)), context);
        gateway.save(new ConceptWriteRequest("MovimentoItem", "item-1b", null,
                Map.of("movimentoId", "mov-1", "quantidade", 20)), context);
        gateway.save(new ConceptWriteRequest("MovimentoItem", "item-2a", null,
                Map.of("movimentoId", "mov-2", "quantidade", 30)), context);

        PanelRuntime runtime = new PanelRuntime(metadataService, null, nestedPanelModel(), gateway, null, null);
        Map<String, Object> loaded = runtime.loadPanel("MovimentoDetailPanel", Map.of(), context);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loaded.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> movimentos = (List<Map<String, Object>>) data.get("movimentos");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flatItems = (List<Map<String, Object>>) data.get("items");

        assertEquals(2, movimentos.size(), "flat parent dataSource unaffected by nesting");
        assertEquals(3, flatItems.size(), "flat child dataSource still carries every row (backward compat)");

        Map<String, Object> mov1 = movimentos.stream().filter(m -> "mov-1".equals(m.get("id"))).findFirst().orElseThrow();
        Map<String, Object> mov2 = movimentos.stream().filter(m -> "mov-2".equals(m.get("id"))).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> mov1Children = (Map<String, Object>) mov1.get("__children");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mov1Items = (List<Map<String, Object>>) mov1Children.get("items");
        assertEquals(2, mov1Items.size(), "mov-1 has exactly its own 2 children nested, no cross-parent leakage");
        assertTrue(mov1Items.stream().allMatch(item -> "mov-1".equals(((Map<?, ?>) item.get("data")).get("movimentoId"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> mov2Children = (Map<String, Object>) mov2.get("__children");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mov2Items = (List<Map<String, Object>>) mov2Children.get("items");
        assertEquals(1, mov2Items.size(), "mov-2 has exactly its own 1 child nested");

        @SuppressWarnings("unchecked")
        List<ConceptGatewayTraceRecord> gatewayTrace = (List<ConceptGatewayTraceRecord>) loaded.get("gatewayTrace");
        long listCalls = gatewayTrace.stream().filter(record -> record.operation() == ConceptGatewayOperation.LIST).count();
        assertEquals(3, listCalls, "1 parent list + 1 filtered child list per parent row (N+1 for 2 parents)");
    }

    @Test
    void loadPanelAppliesDeclaredOrderByDescendingNumericField() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                ConceptGatewaySemanticPolicy.noop(),
                new CollectingTraceSink()
        );
        ExecutionContext context = ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR"));
        gateway.save(new ConceptWriteRequest("Ticket", "t-1", null, Map.of("subject", "Low", "priority", 1)), context);
        gateway.save(new ConceptWriteRequest("Ticket", "t-2", null, Map.of("subject", "High", "priority", 3)), context);
        gateway.save(new ConceptWriteRequest("Ticket", "t-3", null, Map.of("subject", "Medium", "priority", 2)), context);

        PanelRuntime runtime = new PanelRuntime(metadataService, null, orderByPanelModel("priority desc"), gateway, null, null);
        Map<String, Object> loaded = runtime.loadPanel("TicketPanel", Map.of(), context);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loaded.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tickets = (List<Map<String, Object>>) data.get("tickets");

        assertEquals(3, tickets.size());
        assertEquals(3, priorityOf(tickets.get(0)));
        assertEquals(2, priorityOf(tickets.get(1)));
        assertEquals(1, priorityOf(tickets.get(2)));
    }

    @Test
    void loadPanelAppliesDeclaredOrderByAscendingByDefault() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                ConceptGatewaySemanticPolicy.noop(),
                new CollectingTraceSink()
        );
        ExecutionContext context = ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR"));
        gateway.save(new ConceptWriteRequest("Ticket", "t-1", null, Map.of("subject", "Charlie", "priority", 1)), context);
        gateway.save(new ConceptWriteRequest("Ticket", "t-2", null, Map.of("subject", "Alpha", "priority", 3)), context);
        gateway.save(new ConceptWriteRequest("Ticket", "t-3", null, Map.of("subject", "Bravo", "priority", 2)), context);

        PanelRuntime runtime = new PanelRuntime(metadataService, null, orderByPanelModel("subject"), gateway, null, null);
        Map<String, Object> loaded = runtime.loadPanel("TicketPanel", Map.of(), context);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loaded.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tickets = (List<Map<String, Object>>) data.get("tickets");

        assertEquals(3, tickets.size());
        assertEquals("Alpha", subjectOf(tickets.get(0)));
        assertEquals("Bravo", subjectOf(tickets.get(1)));
        assertEquals("Charlie", subjectOf(tickets.get(2)));
    }

    @SuppressWarnings("unchecked")
    private static Object priorityOf(Map<String, Object> record) {
        return ((Map<String, Object>) record.get("data")).get("priority");
    }

    @SuppressWarnings("unchecked")
    private static Object subjectOf(Map<String, Object> record) {
        return ((Map<String, Object>) record.get("data")).get("subject");
    }

    private static CompiledModel orderByPanelModel(String orderBySpec) {
        CompiledQuery query = new CompiledQuery(
                "TicketsOrdered",
                "Ticket",
                null,
                List.of(orderBySpec),
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of()
        );
        CompiledPanel panel = new CompiledPanel(
                "TicketPanel",
                "/tickets",
                "Tickets",
                List.of(new CompiledPanelDataSource("tickets", null, "TicketsOrdered", null, Map.of(), null, null, null)),
                new CompiledPanelLayout("table", List.of(), List.of("subject", "priority"), Map.of()),
                List.of(),
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                null
        );
        return new CompiledModel(
                "panel.runtime.orderby",
                "1.0.0",
                "1.0.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(query),
                List.of(),
                List.of(),
                List.of(panel)
        );
    }

    @Test
    void loadPanelEchoesPanelMetadataForABandPickerToConsume() {
        // AW-P2: a selectors[]-expanded panel's returnMapping must reach the client through
        // loadPanel's response so a bandPicker referencing it by name can use the declared pick
        // contract instead of guessing from overlapping column names.
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                ConceptGatewaySemanticPolicy.noop(),
                new CollectingTraceSink()
        );
        PanelRuntime runtime = new PanelRuntime(metadataService, null, selectorPanelModel(), gateway, null, null);

        Map<String, Object> loaded = runtime.loadPanel(
                "SelecionaRuas", Map.of(), ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR")));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) loaded.get("metadata");
        assertEquals("selector", metadata.get("surface"));
        assertEquals(Boolean.TRUE, metadata.get("multiSelect"));
        @SuppressWarnings("unchecked")
        Map<String, Object> returnMapping = (Map<String, Object>) metadata.get("returnMapping");
        assertEquals("rua", returnMapping.get("local"));
    }

    private static CompiledModel selectorPanelModel() {
        CompiledPanel panel = new CompiledPanel(
                "SelecionaRuas",
                "/select/selecionaruas",
                "LocalArmazenagem",
                List.of(new CompiledPanelDataSource("rows", "LocalArmazenagem", null, null, Map.of(), null, null, null)),
                new CompiledPanelLayout("table", List.of(), List.of("rua", "maxPos"), Map.of()),
                List.of(),
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(
                        "generatedBy", "selector",
                        "surface", "selector",
                        "concept", "LocalArmazenagem",
                        "multiSelect", true,
                        "filters", List.of("area", "rua"),
                        "returnMapping", Map.of("local", "rua", "maxPos", "maxPos")
                ),
                null
        );
        return new CompiledModel(
                "panel.runtime.selector",
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

    private static CompiledModel nestedPanelModel() {
        CompiledPanel panel = new CompiledPanel(
                "MovimentoDetailPanel",
                "/movimentos/detalhe",
                "Detalhe do Movimento",
                List.of(
                        new CompiledPanelDataSource("movimentos", "Movimento", null, null, Map.of(), null, null, null),
                        new CompiledPanelDataSource("items", "MovimentoItem", null, null, Map.of(),
                                "movimentos", "id", "movimentoId")
                ),
                new CompiledPanelLayout("table", List.of(), List.of("tipo"), Map.of()),
                List.of(),
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                null
        );
        return new CompiledModel(
                "panel.runtime.nesting",
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

    private static CompiledModel executablePanelModel() {
        CompiledProcedureStep saveStep = new CompiledProcedureStep(
                "save-contact",
                "saveConcept",
                "saved",
                null,
                null,
                null,
                null,
                "Contact",
                null,
                Map.of("input", "data"),
                "id",
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                Map.of(),
                false,
                Map.of()
        );
        CompiledProcedure procedure = new CompiledProcedure(
                "SubmitContact",
                "Submit a contact through a governed panel action.",
                List.of(),
                List.of(),
                List.of(saveStep),
                null,
                List.of(),
                "summary",
                "write",
                Map.of()
        );
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
                        "submit",
                        "Submit",
                        "procedure",
                        "Contact",
                        "save",
                        "SubmitContact",
                        null,
                        null,
                        null,
                        List.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        null,
                        List.of()
                )),
                Map.of(),
                Map.of(),
                null
        );
        return new CompiledModel(
                "panel.runtime",
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
                List.of(procedure),
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
