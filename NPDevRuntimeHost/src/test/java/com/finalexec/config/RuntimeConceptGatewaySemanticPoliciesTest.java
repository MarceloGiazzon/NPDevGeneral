package com.finalexec.config;

import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewaySemanticException;
import com.npdev.kernel.concepts.ConceptGateways;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeConceptGatewaySemanticPoliciesTest {

    @Test
    void compiledModelEntitiesBecomeRuntimeConceptGatewayPolicy() {
        ConceptGateway gateway = ConceptGateways.inMemory(
                RuntimeConceptGatewaySemanticPolicies.fromCompiledModel(compiledModel())
        );
        ExecutionContext context = ExecutionContext.of("tenant-a", "operator-a");

        ConceptRecord saved = gateway.save(
                new ConceptWriteRequest("Expense", "expense-1", null, Map.of("amount", 25)),
                context
        );

        assertEquals("draft", saved.data().get("status"));

        ConceptGatewaySemanticException exception = assertThrows(
                ConceptGatewaySemanticException.class,
                () -> gateway.save(
                        new ConceptWriteRequest("Expense", "expense-1", null, Map.of("amount", 25, "status", "paid")),
                        context
                )
        );
        assertEquals("CONCEPT_LIFECYCLE_TRANSITION_INVALID", exception.code());
    }

    private static CompiledModel compiledModel() {
        List<CompiledField> fields = List.of(
                new CompiledField("amount", "decimal", "java.math.BigDecimal", false, true, false),
                new CompiledField(
                        "status",
                        "enum",
                        "String",
                        false,
                        true,
                        false,
                        List.of("draft", "submitted", "paid"),
                        null
                )
        );
        CompiledLifecycle lifecycle = new CompiledLifecycle(
                "status",
                List.of(
                        new CompiledStateMachineState("draft", "Draft", true, false, Map.of()),
                        new CompiledStateMachineState("submitted", "Submitted", false, false, Map.of()),
                        new CompiledStateMachineState("paid", "Paid", false, true, Map.of())
                ),
                List.of(new CompiledStateTransition("draft", "submitted", List.of()))
        );
        CompiledEntity entity = new CompiledEntity(
                "Expense",
                "Expense",
                "expenses",
                fields,
                List.of("amount > 0"),
                List.of(),
                lifecycle
        );
        Map<String, CompiledEntity> entities = new LinkedHashMap<>();
        entities.put(entity.getName(), entity);
        return new CompiledModel("runtime.policy", "1.0.0", "1.0.0", entities);
    }
}
