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

    /**
     * R5.5: field-level write authorization -- a non-manager actor changing the "salary" field
     * (whose {@code access.write} requires {@code $user.actorId == 'manager-1'}) is rejected with
     * FIELD_SCOPE_DENIED, the field-scope analogue of ROW_SCOPE_DENIED. The done-when this proves:
     * "a non-manager write on a manager-write field is rejected."
     */
    @Test
    void deniesWriteToFieldWhenFieldAccessWriteRuleFails() {
        ConceptGateway gateway = ConceptGateways.inMemory(payrollPolicy());
        ExecutionContext manager = new ExecutionContext("tenant-a", "manager-1", Map.of(), java.util.Set.of("MANAGER"));
        ExecutionContext nonManager = new ExecutionContext("tenant-a", "operator-a", Map.of(), java.util.Set.of("USER"));

        gateway.save(
                new ConceptWriteRequest("Payroll", "emp-1", null, Map.of("employeeName", "Ana", "salary", 1000)),
                manager
        );

        ConceptGatewayAccessDeniedException exception = assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.save(
                        new ConceptWriteRequest(
                                "Payroll", "emp-1", null,
                                Map.of("employeeName", "Ana", "salary", 5000)
                        ),
                        nonManager
                )
        );

        assertEquals("FIELD_SCOPE_DENIED", exception.code());
        // The whole write is rejected, not silently applied minus the denied field: the stored
        // salary must still be the manager's original value.
        ConceptRecord persisted = gateway.read(new ConceptReadRequest("Payroll", "emp-1", null), manager).orElseThrow();
        assertEquals(1000, persisted.data().get("salary"));
    }

    /**
     * R5.5: a client that resends the WHOLE record (a plain PUT round-tripping a readonly input's
     * current value, the realistic browser shape) must not be rejected for a field it never
     * actually attempted to change -- only a genuinely changed value is evaluated against the
     * field's write rule.
     */
    @Test
    void allowsResubmittingUnchangedFieldValueEvenWhenCallerLacksWriteAccess() {
        ConceptGateway gateway = ConceptGateways.inMemory(payrollPolicy());
        ExecutionContext manager = new ExecutionContext("tenant-a", "manager-1", Map.of(), java.util.Set.of("MANAGER"));
        ExecutionContext nonManager = new ExecutionContext("tenant-a", "operator-a", Map.of(), java.util.Set.of("USER"));

        gateway.save(
                new ConceptWriteRequest("Payroll", "emp-1", null, Map.of("employeeName", "Ana", "salary", 1000)),
                manager
        );

        ConceptRecord saved = gateway.save(
                new ConceptWriteRequest(
                        "Payroll", "emp-1", null,
                        Map.of("employeeName", "Ana Maria", "salary", 1000)
                ),
                nonManager
        );

        assertEquals("Ana Maria", saved.data().get("employeeName"));
        assertEquals(1000, saved.data().get("salary"));
    }

    /**
     * R5.5: field-level read authorization -- a field whose {@code access.read} rule fails for the
     * caller is OMITTED from the response entirely (never returned masked/null), so a denial can
     * never be told apart from "this field was never set."
     */
    @Test
    void omitsFieldFromReadWhenFieldAccessReadRuleFails() {
        ConceptGateway gateway = ConceptGateways.inMemory(payrollPolicy());
        ExecutionContext manager = new ExecutionContext("tenant-a", "manager-1", Map.of(), java.util.Set.of("MANAGER"));
        ExecutionContext nonManager = new ExecutionContext("tenant-a", "operator-a", Map.of(), java.util.Set.of("USER"));

        gateway.save(
                new ConceptWriteRequest("Payroll", "emp-1", null, Map.of("employeeName", "Ana", "salary", 1000)),
                manager
        );

        ConceptRecord managerView = gateway.read(new ConceptReadRequest("Payroll", "emp-1", null), manager).orElseThrow();
        ConceptRecord nonManagerView = gateway.read(new ConceptReadRequest("Payroll", "emp-1", null), nonManager).orElseThrow();

        assertEquals(1000, managerView.data().get("salary"));
        assertFalse(nonManagerView.data().containsKey("salary"));
        // Unrelated fields stay visible -- this isn't a row-level (whole record) denial.
        assertEquals("Ana", nonManagerView.data().get("employeeName"));
    }

    /**
     * REG-195: a row-level {@code access.write} rule using {@code $user.roles.contains(...)} --
     * the only idiom this platform's docs/corpus use for a role-membership check -- must grant
     * access to an actor whose roles satisfy it and deny one whose roles don't, i.e. the two
     * cases must produce DIFFERENT outcomes. Before the fix both returned denied: the function
     * call unconditionally threw "unknown function: contains" against an empty registry, and the
     * fail-closed catch silently turned that into false regardless of the actor's roles.
     */
    @Test
    void grantsRowWriteAccessWhenActorRoleSatisfiesContainsAccessRule() {
        ConceptGateway gateway = ConceptGateways.inMemory(curatedLabelPolicy());
        ExecutionContext curator = new ExecutionContext("tenant-a", "curator-1", Map.of(), java.util.Set.of("CURATOR"));

        ConceptRecord saved = gateway.save(
                new ConceptWriteRequest("Label", "label-1", null, Map.of("text", "hello")),
                curator
        );

        assertEquals("label-1", saved.id());
    }

    @Test
    void deniesRowWriteAccessWhenActorRoleFailsContainsAccessRule() {
        ConceptGateway gateway = ConceptGateways.inMemory(curatedLabelPolicy());
        ExecutionContext nonCurator = new ExecutionContext("tenant-a", "operator-a", Map.of(), java.util.Set.of("USER"));

        assertThrows(
                ConceptGatewayAccessDeniedException.class,
                () -> gateway.save(
                        new ConceptWriteRequest("Label", "label-1", null, Map.of("text", "hello")),
                        nonCurator
                )
        );
    }

    private static ConfiguredConceptGatewaySemanticPolicy curatedLabelPolicy() {
        return new ConfiguredConceptGatewaySemanticPolicy(List.of(
                new ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition(
                        "Label",
                        Map.of(
                                "text",
                                new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                        "text",
                                        false,
                                        List.of(),
                                        null,
                                        null,
                                        null
                                )
                        ),
                        List.of(),
                        null,
                        java.util.Set.of(),
                        new ConfiguredConceptGatewaySemanticPolicy.AccessRules(
                                null,
                                "$user.roles.contains('CURATOR')"
                        )
                )
        ));
    }

    private static ConfiguredConceptGatewaySemanticPolicy payrollPolicy() {
        return new ConfiguredConceptGatewaySemanticPolicy(List.of(
                ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition.of(
                        "Payroll",
                        List.of(
                                new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                        "employeeName",
                                        false,
                                        List.of(),
                                        null,
                                        null,
                                        null
                                ),
                                new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                        "salary",
                                        false,
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        new ConfiguredConceptGatewaySemanticPolicy.AccessRules(
                                                "$user.actorId == 'manager-1'",
                                                "$user.actorId == 'manager-1'"
                                        )
                                )
                        ),
                        List.of(),
                        null
                )
        ));
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
