package com.finalexec.pluginipc;

import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcess;
import com.finalexec.npdev.service.pluginipc.PluginIpcHostSession;
import com.finalexec.npdev.service.pluginipc.PluginProcessResourceLimits;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SEC-3 Model B step 4 (design doc section 3 / section 6 step 4): live-fired proof that a Windows Job
 * Object actually bounds a plugin child process's memory, closing SEC-3's own memory/CPU done-when bar
 * on this OS. {@code @EnabledOnOs(OS.WINDOWS)}: the Linux cgroup path is proven separately by {@link
 * PluginIpcChildProcessLinuxResourceLimitTest}, gated the same way -- a design that only proves out on
 * one OS is not done, per SEC-3's own bar, so this project deliberately keeps two OS-gated test classes
 * rather than one that only ever exercises whichever OS happens to run it.
 */
@EnabledOnOs(OS.WINDOWS)
class PluginIpcChildProcessWindowsResourceLimitTest {

    private final ExecutorService boundedWait = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        boundedWait.shutdownNow();
    }

    @Test
    void aChildThatExceedsItsMemoryCeilingIsKilledByTheJobObject() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);
        // Memory AND CPU both configured together: proves combined-limit configuration does not itself
        // break anything. The memory ceiling is what actually delivers the observable, deterministic kill
        // here -- CPU-rate throttling is not independently behaviorally verified by this test (a live
        // wall-clock throughput measurement would be slow and flaky), only exercised without error.
        PluginProcessResourceLimits limits = new PluginProcessResourceLimits(128, 50);

        try (PluginIpcChildProcess child = PluginIpcChildProcess.start(
                MemoryHogPluginHandler.class.getName(), System.getProperty("java.class.path"), limits
        )) {
            CapabilityResult result = invokeWithBoundedWait(child, hostSession);

            assertTrue(result != null && !result.ok(), () -> "expected the OS to kill the child, got: " + result);
            assertEquals("PLUGIN_EXECUTION_PROCESS_KILLED", result.error().code());
            assertTrue(auditLogStore.records.isEmpty(), "a killed plugin must never have reached the real store");
        }
    }

    @Test
    void aChildWithNoLimitsRequestedBehavesExactlyLikeStep2() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);

        try (PluginIpcChildProcess child = PluginIpcChildProcess.start(
                AuditLogEchoPluginHandler.class.getName(), System.getProperty("java.class.path"), PluginProcessResourceLimits.NONE
        )) {
            CapabilityResult result = child.invoke(hostSession, auditLogContribution(), appendCall(), Map.of());

            assertTrue(result.ok(), () -> "expected success, got: " + result);
            assertEquals(1, auditLogStore.records.size());
        }
    }

    private CapabilityResult invokeWithBoundedWait(PluginIpcChildProcess child, PluginIpcHostSession hostSession)
            throws Exception {
        Future<CapabilityResult> future = boundedWait.submit(
                () -> child.invoke(hostSession, auditLogContribution(), appendCall(), Map.of())
        );
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            fail("child process was not killed by its configured memory ceiling within 30s -- "
                    + "the Job Object limit did not take effect");
            return null;
        }
    }

    private static CapabilityCall appendCall() {
        return new CapabilityCall(
                "auditLog", "AuditLogCapability", "auditlog-inproc", "append",
                List.of(Map.of(
                        "tenantId", "acme", "actorId", "user-1", "action", "LOGIN",
                        "resourceType", "Session", "resourceId", "s-1", "outcome", "SUCCESS"
                ))
        );
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
