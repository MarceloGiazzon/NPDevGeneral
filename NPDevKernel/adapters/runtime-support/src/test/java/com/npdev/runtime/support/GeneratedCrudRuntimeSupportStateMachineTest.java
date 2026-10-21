package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledEntity;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedCrudRuntimeSupportStateMachineTest {

    @Test
    void validateEntityDetailedRejectsMissingRequiredPayloadAndFailedGuard() {
        GeneratedCrudRuntimeSupport support = supportWithPreviousStatus("Scheduled");

        UUID id = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("__id", id);
        payload.put("status", "CheckedIn");

        List<GeneratedCrudRuntimeSupport.InvariantViolationDetail> violations =
                support.validateEntityDetailed("Appointment", payload, (entity, field, value, currentId, rawPayload) -> false);

        assertEquals(2, violations.size());
        assertTrue(violations.stream().anyMatch(v -> "status_transition_requires_field".equals(v.invariant())));
        assertTrue(violations.stream().anyMatch(v -> "status_transition_guard_failed".equals(v.invariant())));
    }

    @Test
    void validateEntityDetailedAllowsTransitionWhenRequiredPayloadAndGuardPass() {
        GeneratedCrudRuntimeSupport support = supportWithPreviousStatus("Scheduled");

        UUID id = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("__id", id);
        payload.put("status", "CheckedIn");
        payload.put("checkInTime", "2026-03-30T10:30:00Z");

        List<GeneratedCrudRuntimeSupport.InvariantViolationDetail> violations =
                support.validateEntityDetailed("Appointment", payload, (entity, field, value, currentId, rawPayload) -> false);

        assertTrue(
                violations.stream().noneMatch(v -> v.invariant() != null && v.invariant().startsWith("status_transition")),
                "Expected no lifecycle transition violations, got: " + violations
        );
    }

    /**
     * REG-275: with in-memory storage there is no EntityManager, so the previous status was never
     * read and the transition check (requiredPayload, guard) skipped itself on every REST write.
     */
    @Test
    void inMemoryStorageReadsThePreviousStatusFromTheConceptGateway() {
        UUID id = UUID.randomUUID();
        GeneratedCrudRuntimeSupport support = inMemorySupport(id, "Scheduled");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("__id", id);
        payload.put("status", "CheckedIn");

        List<GeneratedCrudRuntimeSupport.InvariantViolationDetail> refused =
                support.validateEntityDetailed("Appointment", payload, (entity, field, value, currentId, rawPayload) -> false);
        assertEquals(2, refused.size(), refused.toString());
        assertTrue(refused.stream().anyMatch(v -> "status_transition_requires_field".equals(v.invariant())));
        assertTrue(refused.stream().anyMatch(v -> "status_transition_guard_failed".equals(v.invariant())));

        payload.put("checkInTime", "2026-03-30T10:30:00Z");
        assertTrue(support.validateEntityDetailed("Appointment", payload, (entity, field, value, currentId, rawPayload) -> false)
                .stream().noneMatch(v -> v.invariant() != null && v.invariant().startsWith("status_transition")));
    }

    @Test
    void inMemoryStorageLeavesAnUndeclaredEdgeToTheGatewayAndIgnoresAnUnknownRow() {
        UUID id = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("__id", id);
        payload.put("status", "Completed");

        var undeclaredEdge = inMemorySupport(id, "Scheduled")
                .validateEntityDetailed("Appointment", payload, (entity, field, value, currentId, rawPayload) -> false);
        assertTrue(undeclaredEdge.stream().noneMatch(v -> v.invariant() != null && v.invariant().startsWith("status_transition")),
                "the gateway answers CONCEPT_LIFECYCLE_TRANSITION_INVALID for an undeclared edge: " + undeclaredEdge);

        var unknownRow = inMemorySupport(UUID.randomUUID(), "Scheduled")
                .validateEntityDetailed("Appointment", payload, (entity, field, value, currentId, rawPayload) -> false);
        assertTrue(unknownRow.stream().noneMatch(v -> v.invariant() != null && v.invariant().startsWith("status_transition")),
                "a row the store does not hold is a create, not a transition");
    }

    /** Pigmentampas P9: a REST create omitting the status field failed required(status) instead of starting in the initial state. */
    @Test
    void createPayloadStartsInLifecycleInitialStateButUpdateKeepsExistingStatus() {
        GeneratedCrudRuntimeSupport support = supportWithPreviousStatus("Scheduled");

        Map<String, Object> created = support.buildCreateInvariantPayload("Appointment", Map.of("checkInTime", "2026-03-30T10:30:00Z"));
        assertEquals("Scheduled", created.get("status"));
        Map<String, Object> explicit = support.buildCreateInvariantPayload("Appointment", Map.of("status", "CheckedIn"));
        assertEquals("CheckedIn", explicit.get("status"));

        Map<String, Object> updated = support.buildUpdateInvariantPayload(
                "Appointment", UUID.randomUUID(), Map.of("status", "Completed"), Map.of("checkOutTime", "2026-03-30T11:00:00Z"));
        assertEquals("Completed", updated.get("status"));
    }

    /** The same Appointment model with NO EntityManager and a ConceptGateway that holds one row. */
    private static GeneratedCrudRuntimeSupport inMemorySupport(UUID storedId, String storedStatus) {
        com.npdev.kernel.concepts.ConceptGateway gateway = (com.npdev.kernel.concepts.ConceptGateway) Proxy.newProxyInstance(
                com.npdev.kernel.concepts.ConceptGateway.class.getClassLoader(),
                new Class<?>[]{com.npdev.kernel.concepts.ConceptGateway.class},
                (proxy, method, args) -> {
                    if ("read".equals(method.getName())) {
                        com.npdev.kernel.concepts.ConceptReadRequest request = (com.npdev.kernel.concepts.ConceptReadRequest) args[0];
                        return storedId.toString().equals(request.id())
                                ? java.util.Optional.of(new com.npdev.kernel.concepts.ConceptRecord(
                                        request.conceptName(), request.id(), request.tenantId(),
                                        Map.of("status", storedStatus), 1L))
                                : java.util.Optional.empty();
                    }
                    return null;
                });
        return supportWith(null).withConceptGateway(gateway);
    }

    private static GeneratedCrudRuntimeSupport supportWithPreviousStatus(String previousStatus) {
        return supportWith(stubEntityManager(previousStatus));
    }

    private static GeneratedCrudRuntimeSupport supportWith(EntityManager entityManager) {
        CompiledEntity appointment = new CompiledEntity(
                "Appointment",
                "Appointment",
                "appointments",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("status", "enum", "String", false, true, false, List.of("Scheduled", "CheckedIn", "Completed"), null, null, null, null, List.of()),
                        new CompiledField("checkInTime", "string", "String", false, false, false),
                        new CompiledField("checkOutTime", "string", "String", false, false, false)
                ),
                List.of(),
                List.of(),
                new CompiledLifecycle(
                        "status",
                        List.of(
                                new CompiledStateMachineState("Scheduled", "Scheduled", true, false, Map.of("lane", "active")),
                                new CompiledStateMachineState("CheckedIn", "Checked In", false, false, Map.of("lane", "active")),
                                new CompiledStateMachineState("Completed", "Completed", false, true, Map.of("lane", "terminal"))
                        ),
                        List.of(
                                new CompiledStateTransition(
                                        "Scheduled",
                                        "CheckedIn",
                                        List.of("checkInTime"),
                                        "AppointmentCheckedIn",
                                        "checkInTime != null",
                                        "Check In",
                                        Map.of("intent", "check-in")
                                ),
                                new CompiledStateTransition(
                                        "CheckedIn",
                                        "Completed",
                                        List.of("checkOutTime"),
                                        "AppointmentCompleted",
                                        "checkOutTime != null",
                                        "Complete Appointment",
                                        Map.of("intent", "complete")
                                )
                        )
                )
        );

        Map<String, CompiledEntity> entities = new LinkedHashMap<>();
        entities.put(appointment.getName(), appointment);
        CompiledModel compiledModel = new CompiledModel("demo", "1.0.0", "v1", entities);
        KernelRunner kernelRunner = new KernelRunner((EventBus) event -> { }, new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }
        });

        return new GeneratedCrudRuntimeSupport(
                () -> compiledModel,
                kernelRunner,
                entityManager,
                null,
                null,
                null,
                new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                new RuntimeInvariantEngineFactory() {
                    @Override
                    public InvariantEngine create(
                            UniqueValueLookup uniqueValueLookup,
                            ConflictLookup conflictLookup
                    ) {
                        return new InvariantEngine() {
                            @Override
                            public List<String> evaluate(String entityName, Object payload) {
                                return List.of();
                            }
                        };
                    }
                }
        );
    }

    private static EntityManager stubEntityManager(String previousStatus) {
        Query query = (Query) Proxy.newProxyInstance(
                Query.class.getClassLoader(),
                new Class<?>[]{Query.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setParameter", "setMaxResults" -> proxy;
                    case "getResultList" -> List.of(previousStatus);
                    default -> null;
                }
        );

        return (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (proxy, method, args) -> {
                    if ("createNativeQuery".equals(method.getName())) {
                        return query;
                    }
                    return null;
                }
        );
    }
}
