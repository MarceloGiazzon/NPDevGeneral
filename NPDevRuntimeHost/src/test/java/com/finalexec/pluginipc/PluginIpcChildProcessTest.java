package com.finalexec.pluginipc;

import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcess;
import com.finalexec.npdev.service.pluginipc.PluginIpcHostSession;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-3 / Model B step 2: the step-1 prototype ({@link PluginIpcRoundTripTest}) proved the protocol and
 * allowlist entirely in-process over piped streams; this proves the same sequences over a REAL OS process
 * boundary -- a real {@code ProcessBuilder} child, spawned per invoke, talking over its actual stdin/stdout
 * pipes. Covers exactly the two live-fired tests SEC-3's own done-when bar names (design doc section 6,
 * step 2 / section 4): a plugin that calls {@code System.exit} does not take down the host process, and a
 * forbidden callback is actually blocked across the process boundary. A third test proves the ordinary
 * success path also survives a real process round trip, not just piped streams.
 */
class PluginIpcChildProcessTest {

    @Test
    void auditLogPluginCallsBackIntoItsOwnDeclaredCapabilityOverARealChildProcess() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);
        CapabilityCall call = new CapabilityCall(
                "auditLog", "AuditLogCapability", "auditlog-inproc", "append",
                List.of(Map.of(
                        "tenantId", "acme", "actorId", "user-1", "action", "LOGIN",
                        "resourceType", "Session", "resourceId", "s-1", "outcome", "SUCCESS"
                ))
        );

        try (PluginIpcChildProcess child = PluginIpcChildProcess.start(AuditLogEchoPluginHandler.class.getName())) {
            CapabilityResult result = child.invoke(hostSession, auditLogContribution(), call, Map.of());

            assertTrue(result.ok(), () -> "expected success, got: " + result);
            assertEquals(1, auditLogStore.records.size());
            assertEquals("LOGIN", auditLogStore.records.get(0).action());
        }
    }

    @Test
    void aCallbackToACapabilityThePluginNeverDeclaredIsDeniedAcrossARealChildProcess() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);
        CapabilityCall call = new CapabilityCall("auditLog", "AuditLogCapability", "auditlog-inproc", "append", List.of());

        try (PluginIpcChildProcess child = PluginIpcChildProcess.start(HostileCallbackPluginHandler.class.getName())) {
            CapabilityResult result = child.invoke(hostSession, auditLogContribution(), call, Map.of());

            assertFalse(result.ok());
            assertEquals("PLUGIN_CALLBACK_NOT_DECLARED", result.error().code());
            assertTrue(auditLogStore.records.isEmpty(), "denied callback must never reach the real store");
        }
    }

    @Test
    void aPluginThatCallsSystemExitKillsOnlyItsOwnProcessAndTheHostSurvivesToRunMoreInvocations() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);
        CapabilityCall call = new CapabilityCall("auditLog", "AuditLogCapability", "auditlog-inproc", "append", List.of());

        try (PluginIpcChildProcess child = PluginIpcChildProcess.start(SystemExitPluginHandler.class.getName())) {
            CapabilityResult result = child.invoke(hostSession, auditLogContribution(), call, Map.of());

            assertFalse(result.ok());
            assertEquals("PLUGIN_EXECUTION_PROCESS_KILLED", result.error().code());
            assertEquals(1, result.error().details().get("exitValue"));
        }

        // The host process (this very test JVM) was never touched by the child's System.exit -- proven by
        // being able to run a fresh, ordinary invocation against a brand new child right afterward.
        CapabilityCall secondCall = new CapabilityCall(
                "auditLog", "AuditLogCapability", "auditlog-inproc", "append",
                List.of(Map.of(
                        "tenantId", "acme", "actorId", "user-2", "action", "LOGOUT",
                        "resourceType", "Session", "resourceId", "s-2", "outcome", "SUCCESS"
                ))
        );
        try (PluginIpcChildProcess secondChild = PluginIpcChildProcess.start(AuditLogEchoPluginHandler.class.getName())) {
            CapabilityResult secondResult = secondChild.invoke(hostSession, auditLogContribution(), secondCall, Map.of());
            assertTrue(secondResult.ok(), () -> "expected success, got: " + secondResult);
            assertEquals(1, auditLogStore.records.size(), "only the second, successful invocation should have reached the store");
        }
    }

    private static PluginIpcHostSession hostSession(AuditLogStore store) {
        return new PluginIpcHostSession(
                auditLogRegistry(), new PluginExecutionPolicyEvaluator(null, "dev", "", "", "", ""),
                call -> dispatchToRealAuditLogStore(store, call)
        );
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
