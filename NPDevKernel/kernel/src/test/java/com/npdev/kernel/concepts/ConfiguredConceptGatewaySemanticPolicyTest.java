package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfiguredConceptGatewaySemanticPolicyTest {

    @Test
    void appliesLifecycleDefaultAndEvaluatesWriteInvariants() {
        ConceptGateway gateway = ConceptGateways.inMemory(expensePolicy());
        ExecutionContext context = ExecutionContext.of("tenant-a", "operator-a");

        ConceptRecord saved = gateway.save(
                new ConceptWriteRequest("Expense", "expense-1", null, Map.of("amount", 25)),
                context
        );

        assertEquals("draft", saved.data().get("status"));
        assertEquals("null->draft", gateway.explain().get(0).lifecycleTransition());
    }

    @Test
    void rejectsMissingRequiredFieldsBeforePersistence() {
        ConceptGateway gateway = ConceptGateways.inMemory(expensePolicy());

        ConceptGatewaySemanticException exception = assertThrows(
                ConceptGatewaySemanticException.class,
                () -> gateway.save(
                        new ConceptWriteRequest("Expense", "expense-1", null, Map.of("status", "draft")),
                        ExecutionContext.of("tenant-a", "operator-a")
                )
        );

        assertEquals("CONCEPT_FIELD_REQUIRED", exception.code());
    }

    @Test
    void rejectsInvalidLifecycleTransitions() {
        ConceptGateway gateway = ConceptGateways.inMemory(expensePolicy());
        ExecutionContext context = ExecutionContext.of("tenant-a", "operator-a");
        gateway.save(new ConceptWriteRequest("Expense", "expense-1", null, Map.of("amount", 25)), context);

        ConceptGatewaySemanticException exception = assertThrows(
                ConceptGatewaySemanticException.class,
                () -> gateway.save(
                        new ConceptWriteRequest("Expense", "expense-1", null, Map.of("amount", 25, "status", "paid")),
                        context
                )
        );

        assertEquals("CONCEPT_LIFECYCLE_TRANSITION_INVALID", exception.code());
    }

    @Test
    void filtersHiddenFieldsOnReadAndList() {
        ConceptGateway gateway = ConceptGateways.inMemory(expensePolicy());
        ExecutionContext context = ExecutionContext.of("tenant-a", "operator-a");
        gateway.save(new ConceptWriteRequest(
                "Expense",
                "expense-1",
                null,
                Map.of("amount", 25, "internalNote", "hidden")
        ), context);

        ConceptRecord read = gateway.read(new ConceptReadRequest("Expense", "expense-1", null), context).orElseThrow();
        ConceptRecord listed = gateway.list(new ConceptListRequest("Expense", null), context).get(0);

        assertFalse(read.data().containsKey("internalNote"));
        assertFalse(listed.data().containsKey("internalNote"));
    }

    @Test
    void allowsUniqueByInvariantWhenSubfieldValuesAreDistinct() {
        ConceptGateway gateway = ConceptGateways.inMemory(patientPolicy());
        ExecutionContext context = ExecutionContext.of("tenant-a", "operator-a");

        ConceptRecord saved = gateway.save(
                new ConceptWriteRequest("Patient", "patient-1", null, Map.of(
                        "allergies", List.of(
                                Map.of("code", "PEANUT"),
                                Map.of("code", "LATEX")
                        )
                )),
                context
        );

        assertEquals("patient-1", saved.id());
    }

    @Test
    void rejectsUniqueByInvariantWhenSubfieldValuesRepeat() {
        ConceptGateway gateway = ConceptGateways.inMemory(patientPolicy());
        ExecutionContext context = ExecutionContext.of("tenant-a", "operator-a");

        ConceptGatewaySemanticException exception = assertThrows(
                ConceptGatewaySemanticException.class,
                () -> gateway.save(
                        new ConceptWriteRequest("Patient", "patient-1", null, Map.of(
                                "allergies", List.of(
                                        Map.of("code", "PEANUT"),
                                        Map.of("code", "PEANUT")
                                )
                        )),
                        context
                )
        );

        assertEquals("CONCEPT_INVARIANT_REJECTED", exception.code());
    }

    private static ConfiguredConceptGatewaySemanticPolicy patientPolicy() {
        return new ConfiguredConceptGatewaySemanticPolicy(List.of(
                ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition.of(
                        "Patient",
                        List.of(
                                new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                        "allergies",
                                        false,
                                        List.of(),
                                        null,
                                        null,
                                        null
                                )
                        ),
                        List.of(new ConfiguredConceptGatewaySemanticPolicy.InvariantDefinition(
                                "allergyCodesUnique",
                                "allergies.uniqueBy(code)"
                        )),
                        null
                )
        ));
    }

    private static ConfiguredConceptGatewaySemanticPolicy expensePolicy() {
        return new ConfiguredConceptGatewaySemanticPolicy(List.of(
                ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition.of(
                        "Expense",
                        List.of(
                                new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                        "amount",
                                        true,
                                        List.of(),
                                        null,
                                        null,
                                        null
                                ),
                                new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                        "status",
                                        true,
                                        List.of("draft", "submitted", "paid"),
                                        null,
                                        null,
                                        null
                                ),
                                new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                        "internalNote",
                                        false,
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        true
                                )
                        ),
                        List.of(new ConfiguredConceptGatewaySemanticPolicy.InvariantDefinition(
                                "positiveAmount",
                                "amount > 0"
                        )),
                        ConfiguredConceptGatewaySemanticPolicy.LifecycleDefinition.of(
                                "status",
                                "draft",
                                List.of("draft", "submitted", "paid"),
                                List.of(new ConfiguredConceptGatewaySemanticPolicy.StateTransition("draft", "submitted"))
                        )
                )
        ));
    }
}
