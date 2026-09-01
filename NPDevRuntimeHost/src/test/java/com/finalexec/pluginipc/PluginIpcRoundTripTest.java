package com.finalexec.pluginipc;

import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.PluginIpcCallbackClient;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildRuntime;
import com.finalexec.npdev.service.pluginipc.PluginIpcHostSession;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;
import com.npdev.kernel.ports.CapabilityAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-3 / Model B step-1 prototype: proves the IPC protocol
 * (docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 1) and the callback allowlist end to
 * end, entirely in-process over piped streams standing in for a real child process's stdin/stdout --
 * exactly the design doc's recommended step 1, ahead of any real process spawning (section 6, step 1).
 * Host and child run on separate threads, as they would across a real process boundary, since both
 * block on I/O over the same pipe pair at different points in the exchange.
 */
class PluginIpcRoundTripTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void auditLogPluginCallsBackIntoItsOwnDeclaredCapabilityAndTheHostExecutesItForReal() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        RuntimePluginAdapterRegistry registry = auditLogRegistry();
        RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution = auditLogContribution();
        PluginExecutionPolicyEvaluator policyEvaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");

        PipedOutputStream hostToChildOut = new PipedOutputStream();
        PipedInputStream hostToChildIn = new PipedInputStream(hostToChildOut);
        PipedOutputStream childToHostOut = new PipedOutputStream();
        PipedInputStream childToHostIn = new PipedInputStream(childToHostOut);

        Future<?> childTask = executor.submit(() ->
                new PluginIpcChildRuntime(hostToChildIn, childToHostOut)
                        .runOnce(PluginIpcRoundTripTest::auditLogPluginHandler));

        PluginIpcHostSession hostSession = new PluginIpcHostSession(
                registry, policyEvaluator, call -> dispatchToRealAuditLogStore(auditLogStore, call)
        );
        CapabilityCall call = new CapabilityCall(
                "auditLog", "AuditLogCapability", "auditlog-inproc", "append",
                List.of(Map.of(
                        "tenantId", "acme", "actorId", "user-1", "action", "LOGIN",
                        "resourceType", "Session", "resourceId", "s-1", "outcome", "SUCCESS"
                ))
        );

        CapabilityResult result = hostSession.invoke(contribution, call, Map.of(), childToHostIn, hostToChildOut);

        childTask.get(5, TimeUnit.SECONDS);
        assertTrue(result.ok(), () -> "expected success, got: " + result);
        assertEquals(1, auditLogStore.records.size());
        assertEquals("LOGIN", auditLogStore.records.get(0).action());
    }

    @Test
    void aCallbackToACapabilityThePluginNeverDeclaredIsDeniedAndNeverReachesTheRealStore() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        RuntimePluginAdapterRegistry registry = auditLogRegistry();
        RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution = auditLogContribution();
        PluginExecutionPolicyEvaluator policyEvaluator = new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", "");

        PipedOutputStream hostToChildOut = new PipedOutputStream();
        PipedInputStream hostToChildIn = new PipedInputStream(hostToChildOut);
        PipedOutputStream childToHostOut = new PipedOutputStream();
        PipedInputStream childToHostIn = new PipedInputStream(childToHostOut);

        Future<?> childTask = executor.submit(() ->
                new PluginIpcChildRuntime(hostToChildIn, childToHostOut)
                        .runOnce(PluginIpcRoundTripTest::hostilePluginHandler));

        PluginIpcHostSession hostSession = new PluginIpcHostSession(
                registry, policyEvaluator, call -> dispatchToRealAuditLogStore(auditLogStore, call)
        );
        CapabilityCall call = new CapabilityCall("auditLog", "AuditLogCapability", "auditlog-inproc", "append", List.of());

        CapabilityResult result = hostSession.invoke(contribution, call, Map.of(), childToHostIn, hostToChildOut);

        childTask.get(5, TimeUnit.SECONDS);
        assertFalse(result.ok());
        assertEquals("PLUGIN_CALLBACK_NOT_DECLARED", result.error().code());
        assertTrue(auditLogStore.records.isEmpty(), "denied callback must never reach the real store");
    }

    /** Stand-in for a generated auditLog plugin handler: calls back into its own declared capability. */
    private static CapabilityAdapter auditLogPluginHandler(PluginIpcCallbackClient callbackClient) {
        return new CapabilityAdapter() {
            @Override
            public String adapterId() {
                return "auditlog-inproc";
            }

            @Override
            public String capability() {
                return "auditLog";
            }

            @Override
            public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
                return callbackClient.callBack("auditLog", "append", call.args());
            }
        };
    }

    /** A plugin whose declared job is auditLog trying to call back into an undeclared capability. */
    private static CapabilityAdapter hostilePluginHandler(PluginIpcCallbackClient callbackClient) {
        return new CapabilityAdapter() {
            @Override
            public String adapterId() {
                return "auditlog-inproc";
            }

            @Override
            public String capability() {
                return "auditLog";
            }

            @Override
            public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
                return callbackClient.callBack("persistence", "dropAll", List.of());
            }
        };
    }

    private static CapabilityResult dispatchToRealAuditLogStore(AuditLogStore store, CapabilityCall call) {
        if (!"auditLog".equalsIgnoreCase(call.capability()) || !"append".equalsIgnoreCase(call.operation())) {
            return CapabilityResult.failure(
                    "PLUGIN_EXECUTION_FAILED",
                    "no host dispatch wired for " + call.capability() + "." + call.operation(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of()
            );
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) call.input();
        AuditRecord record = AuditRecord.create(
                (String) fields.get("tenantId"),
                (String) fields.get("actorId"),
                Set.of("USER"),
                (String) fields.get("action"),
                (String) fields.get("resourceType"),
                (String) fields.get("resourceId"),
                (String) fields.get("outcome"),
                null,
                Map.of(),
                Map.of()
        );
        store.append(record);
        return CapabilityResult.success(Map.of("auditId", record.auditId()));
    }

    private static RuntimePluginAdapterRegistry.RegisteredAdapterContribution auditLogContribution() {
        return new RuntimePluginAdapterRegistry.RegisteredAdapterContribution(
                "auditlog-plugin", "1.0.0", "auditLog", "append", "auditlog-inproc",
                "auditLog.append", "class", "com.example.AuditLogHandler"
        );
    }

    private static RuntimePluginAdapterRegistry auditLogRegistry() {
        RuntimePluginManifest manifest = new RuntimePluginManifest(
                "test-manifest.json", "1",
                List.of(new RuntimePluginManifest.PluginContribution(
                        "auditlog-plugin", "Audit Log Plugin", "1.0.0", true,
                        List.of(new RuntimePluginManifest.AdapterContribution(
                                "auditLog", "append", "auditlog-inproc", "auditLog.append",
                                new RuntimePluginManifest.ImplementationRef("class", "com.example.AuditLogHandler")
                        ))
                ))
        );
        return new RuntimePluginAdapterRegistry(manifest);
    }

    private static final class InMemoryAuditLogStore implements AuditLogStore {
        private final List<AuditRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void append(AuditRecord record) {
            records.add(record);
        }

        @Override
        public List<AuditRecord> search(AuditQuery query) {
            return List.copyOf(records);
        }
    }
}
