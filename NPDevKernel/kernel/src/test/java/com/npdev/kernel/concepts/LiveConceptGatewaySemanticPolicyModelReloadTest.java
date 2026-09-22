package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.SequenceAllocator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-236: proves {@link LiveConceptGatewaySemanticPolicy} observes a hot model reload
 * without being reconstructed -- the same {@link LiveConceptGatewaySemanticPolicy} instance must
 * enforce the OLD model's rules before a swap and the NEW model's rules after, via its own
 * identity-compare cache against a plain {@link java.util.function.Supplier}.
 */
class LiveConceptGatewaySemanticPolicyModelReloadTest {

    @Test
    void enforcesNewlyRequiredFieldAfterModelSupplierSwapWithoutReconstruction() {
        CompiledModel nameNotRequired = widgetModel(false);
        CompiledModel nameRequired = widgetModel(true);

        AtomicReference<CompiledModel> liveModel = new AtomicReference<>(nameNotRequired);
        LiveConceptGatewaySemanticPolicy policy =
                new LiveConceptGatewaySemanticPolicy(liveModel::get, SequenceAllocator.inMemory());

        ConceptGatewayRequestContext request = new ConceptGatewayRequestContext(
                ConceptGatewayOperation.SAVE,
                "Widget",
                "widget-1",
                "tenant-a",
                Map.of(),
                ExecutionContext.of("tenant-a", "operator-a"),
                java.util.Optional.empty()
        );

        ConceptSemanticDecision beforeReload = policy.normalizeAndValidate(request);
        assertTrue(beforeReload.allowed(), "name is not required yet, so an empty payload must be allowed");

        liveModel.set(nameRequired);

        ConceptSemanticDecision afterReload = policy.normalizeAndValidate(request);
        assertFalse(afterReload.allowed(), "name is now required, so the SAME policy instance must reject an empty payload");
        assertEquals("CONCEPT_FIELD_REQUIRED", afterReload.code());
    }

    private static CompiledModel widgetModel(boolean nameRequired) {
        CompiledField nameField = new CompiledField("name", "string", "String", false, nameRequired, false);
        CompiledConcept widget = new CompiledConcept("Widget", "Widget", "widgets", List.of(nameField));
        Map<String, CompiledConcept> entities = new LinkedHashMap<>();
        entities.put(widget.getName(), widget);
        return new CompiledModel("live-reload.test", "1.0.0", entities);
    }
}
