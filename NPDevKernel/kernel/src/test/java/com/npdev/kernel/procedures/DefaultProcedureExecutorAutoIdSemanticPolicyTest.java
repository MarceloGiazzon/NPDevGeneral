package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.FieldDefinition;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 5 (final item / REG-78 investigation): found live, not assumed -- {@code saveConcept}'s
 * blank-idRef auto-generate fallback (Wave 1B) and {@code patchConcept}'s {@code createIfMissing}
 * create half (REG-77) both resolved/generated a fresh id and passed it as the {@code
 * ConceptWriteRequest}'s OWN {@code id} parameter, but never folded it into the request's {@code
 * data} map. Every kernel unit test up to this point wired {@code DefaultConceptGateway} with a
 * permissive/noop semantic policy, which never noticed. A REAL governed gateway
 * ({@code ConfiguredConceptGatewaySemanticPolicy}, exactly what every generated WmsOffice-style app
 * actually runs) requires every declared required field -- including "id", declared required on
 * essentially every real concept -- to be present IN the data map itself, so every fresh-record
 * write through either step was silently denied CONCEPT_FIELD_REQUIRED against a real app. Caught
 * live while proving SyncOcupacaoProcedure (the REG-78 arithmetic-primitive closure) end to end
 * against a real generated WmsOffice boot, not in an isolated unit test.
 */
class DefaultProcedureExecutorAutoIdSemanticPolicyTest {

    private static final ExecutionContext CTX = ExecutionContext.of("tenant-a", "actor-a");
    private static final EventBus NOOP_EVENT_BUS = event -> { };

    private static DefaultProcedureExecutor newExecutorWithRealSemanticPolicy() {
        ConceptDefinition widget = ConceptDefinition.of(
                "Widget",
                List.of(
                        new FieldDefinition("id", true, List.of(), null, null, null),
                        new FieldDefinition("name", true, List.of(), null, null, null)
                ),
                List.of(),
                null
        );
        ConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                new ConfiguredConceptGatewaySemanticPolicy(List.of(widget)),
                record -> { }
        );
        return new DefaultProcedureExecutor(gateway, (call, state) -> CapabilityResult.success(null), NOOP_EVENT_BUS);
    }

    @Test
    void saveConceptWithNoIdRefAutoGeneratesAnIdThatSatisfiesARealRequiredIdField() {
        DefaultProcedureExecutor executor = newExecutorWithRealSemanticPolicy();
        ProcedureDefinition definition = new ProcedureDefinition(
                "CreateWidget",
                List.of(ProcedureStep.saveConcept("create-widget", "Widget", null, "$widgetData", "saved"))
        );

        ProcedureExecutionResult result = executor.execute(
                definition, Map.of("widgetData", Map.of("name", "Gadget")), CTX);

        assertTrue(result.ok(), "a fresh record with no client-supplied id must save through a REAL "
                + "governed gateway, not be denied CONCEPT_FIELD_REQUIRED for its own auto-generated id: "
                + result.failureMessage());
    }

    @Test
    void patchConceptCreateIfMissingAutoGeneratesAnIdThatSatisfiesARealRequiredIdField() {
        DefaultProcedureExecutor executor = newExecutorWithRealSemanticPolicy();
        ProcedureDefinition definition = new ProcedureDefinition(
                "EnsureWidget",
                List.of(ProcedureStep.patchConcept(
                        "ensure-widget", "Widget", "$missingId",
                        Map.of("name", "Gadget"), "ensured", true))
        );

        ProcedureExecutionResult result = executor.execute(definition, Map.of(), CTX);

        assertTrue(result.ok(), "createIfMissing's new-record path must satisfy a real gateway's "
                + "required-id check on its own freshly generated id, not be denied "
                + "CONCEPT_FIELD_REQUIRED: " + result.failureMessage());
    }

    @Test
    void saveConceptNeverOverridesAClientSuppliedIdAlreadyPresentInTheData() {
        DefaultProcedureExecutor executor = newExecutorWithRealSemanticPolicy();
        ProcedureDefinition definition = new ProcedureDefinition(
                "CreateWidgetWithExplicitId",
                List.of(ProcedureStep.saveConcept("create-widget", "Widget", "$explicitId", "$widgetData", "saved"))
        );

        ProcedureExecutionResult result = executor.execute(
                definition,
                Map.of("explicitId", "widget-42", "widgetData", Map.of("id", "widget-42", "name", "Gadget")),
                CTX);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals("widget-42", ((ConceptRecord) result.state().get("saved")).id());
    }
}
