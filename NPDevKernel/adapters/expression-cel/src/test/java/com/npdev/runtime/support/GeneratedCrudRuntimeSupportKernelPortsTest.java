package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import com.npdev.kernel.security.PermissionDecision;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedCrudRuntimeSupportKernelPortsTest {

    @Test
    void resolveCurrentCrudContext_fallsBackToAnonymousWithoutRequest() {
        GeneratedCrudRuntimeSupport support = supportWith(
                AuditLogStore.noop(), PermissionEvaluator.allowAll(), IdempotencyStore.noop());

        ExecutionContext ctx = support.resolveCurrentCrudContext();

        assertEquals("default", ctx.tenantId());
        assertEquals("anonymous", ctx.actorId());
    }

    @Test
    void checkCrudPermission_throwsAndAuditsDenyWhenDenied() {
        List<AuditRecord> captured = new ArrayList<>();
        AuditLogStore capturingStore = new AuditLogStore() {
            @Override
            public void append(AuditRecord record) {
                captured.add(record);
            }

            @Override
            public List<AuditRecord> search(AuditQuery query) {
                return List.of();
            }
        };
        PermissionEvaluator denyAll = (subject, requirement) -> PermissionDecision.deny("test_deny", "denied");

        GeneratedCrudRuntimeSupport support = supportWith(capturingStore, denyAll, IdempotencyStore.noop());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                support.checkCrudPermission("Order", "CREATE", ExecutionContext.anonymous()));

        assertEquals(403, ex.getStatusCode().value());
        assertEquals(1, captured.size(), "audit must be called before throwing");
        assertEquals("DENY", captured.get(0).outcome());
    }

    @Test
    void auditCrudMutation_doesNotThrowWhenAuditStoreFails() {
        AuditLogStore failingStore = new AuditLogStore() {
            @Override
            public void append(AuditRecord record) {
                throw new RuntimeException("store down");
            }

            @Override
            public List<AuditRecord> search(AuditQuery query) {
                return List.of();
            }
        };

        GeneratedCrudRuntimeSupport support = supportWith(
                failingStore, PermissionEvaluator.allowAll(), IdempotencyStore.noop());

        assertDoesNotThrow(() ->
                support.auditCrudMutation("Order", "CREATE", UUID.randomUUID().toString(), "ALLOW",
                        ExecutionContext.anonymous()));
    }

    @Test
    void checkCrudIdempotency_returnsExistingWhenKeyRecorded() {
        String storedId = UUID.randomUUID().toString();
        IdempotencyRecord record = new IdempotencyRecord(
                "default", "key-abc", "crud.Order", "create",
                System.currentTimeMillis(), IdempotencyRecord.STATUS_SUCCESS, storedId, null);

        IdempotencyStore recordingStore = new IdempotencyStore() {
            @Override
            public Optional<IdempotencyRecord> find(String tenantId, String capability,
                                                     String operation, String idempotencyKey) {
                return Optional.of(record);
            }

            @Override
            public void saveSuccess(String tenantId, String capability, String operation,
                                    String idempotencyKey, String resultJsonRedacted, long createdAtMs) {
            }

            @Override
            public void saveFailure(String tenantId, String capability, String operation,
                                    String idempotencyKey, String errorCode, long createdAtMs) {
            }
        };

        GeneratedCrudRuntimeSupport support = supportWith(
                AuditLogStore.noop(), PermissionEvaluator.allowAll(), recordingStore);

        Optional<String> result = support.checkCrudIdempotency("default", "Order", "key-abc");

        assertTrue(result.isPresent());
        assertEquals(storedId, result.get());
    }

    @Test
    void recordCrudIdempotencySuccess_noopsWhenKeyBlank() {
        boolean[] saveCalled = {false};
        IdempotencyStore spyStore = new IdempotencyStore() {
            @Override
            public Optional<IdempotencyRecord> find(String tenantId, String capability,
                                                     String operation, String idempotencyKey) {
                return Optional.empty();
            }

            @Override
            public void saveSuccess(String tenantId, String capability, String operation,
                                    String idempotencyKey, String resultJsonRedacted, long createdAtMs) {
                saveCalled[0] = true;
            }

            @Override
            public void saveFailure(String tenantId, String capability, String operation,
                                    String idempotencyKey, String errorCode, long createdAtMs) {
            }
        };

        GeneratedCrudRuntimeSupport support = supportWith(
                AuditLogStore.noop(), PermissionEvaluator.allowAll(), spyStore);

        support.recordCrudIdempotencySuccess("default", "Order", UUID.randomUUID().toString(), "   ");

        assertFalse(saveCalled[0], "saveSuccess must not be called for blank idempotencyKey");
    }

    private static GeneratedCrudRuntimeSupport supportWith(
            AuditLogStore auditLogStore,
            PermissionEvaluator permissionEvaluator,
            IdempotencyStore idempotencyStore) {

        CompiledModel compiledModel = new CompiledModel("demo", "1.0.0", "v1", new LinkedHashMap<>());
        KernelRunner kernelRunner = new KernelRunner(
                (EventBus) event -> {},
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }
        );

        return new GeneratedCrudRuntimeSupport(
                compiledModel,
                kernelRunner,
                null,
                null,
                null,
                null,
                new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                new RuntimeInvariantEngineFactory() {
                    @Override
                    public InvariantEngine create(
                            RuntimeInvariantEngineFactory.UniqueValueLookup uniqueValueLookup,
                            RuntimeInvariantEngineFactory.ConflictLookup conflictLookup
                    ) {
                        return new InvariantEngine() {
                            @Override
                            public List<String> evaluate(String entityName, Object payload) {
                                return List.of();
                            }
                        };
                    }
                },
                auditLogStore,
                permissionEvaluator,
                idempotencyStore
        );
    }
}
