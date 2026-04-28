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

    private static GeneratedCrudRuntimeSupport supportWithPreviousStatus(String previousStatus) {
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
                compiledModel,
                kernelRunner,
                stubEntityManager(previousStatus),
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
