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
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGatewayOperation;
import com.npdev.kernel.concepts.ConceptGatewaySemanticPolicy;
import com.npdev.kernel.concepts.ConceptGatewayTraceRecord;
import com.npdev.kernel.concepts.ConceptGatewayTraceSink;
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
                List.of(new CompiledPanelDataSource("contacts", "Contact", null, null, Map.of())),
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
                        Map.of()
                )),
                Map.of(),
                Map.of()
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
