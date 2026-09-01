package com.finalexec.npdev.service.pluginipc;

import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
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
 * SEC-3 Model B step 4, Linux half: live-fired proof that a cgroup v2 ceiling actually kills a
 * plugin child process that exceeds its configured memory limit.
 *
 * <p>Runs only on Linux, and only where a real ceiling can actually be applied -- see
 * {@link #assumeARealCeilingCanBeApplied()}. That guard is the whole difference between this being
 * a proof and being a green tick: without it, a container started without
 * {@code --privileged --cgroupns=private} would silently exercise the no-op degrade path, the
 * child would be killed by nothing at all, and a hang would read as "the test is slow".</p>
 *
 * <p>Lives in runtimehost-core's own independently-buildable test source set (precedent:
 * {@link PluginNoOpResourceLimiterTest}), NOT the template-app test tree at
 * NPDevRuntimeHost/src/test/java/com/finalexec/pluginipc/, which only compiles inside an assembled
 * generated app and needs the full generate+assemble pipeline (~13 min) before a single assertion
 * runs. That template-tree copy is left in place deliberately (see its own javadoc) rather than
 * deleted here, to avoid perturbing the RuntimeHost coverage ratchet in the same commit as a new
 * Docker proof; twin-pair rule plugin-linux-proof-fixture-duplication in
 * scripts/quality/twin-pair-registry.json keeps the two test classes and their two handler fixture
 * pairs from silently diverging. SEC-3 fork (a), 2026-09-01: this copy is the one actually run,
 * live-fired inside scripts/quality/linux-plugin-proof/'s Docker image.</p>
 */
@EnabledOnOs(OS.LINUX)
class PluginIpcChildProcessLinuxResourceLimitTest {

    /**
     * 256 MB, NOT 128. See {@link #aWellBehavedChildSurvivesTheSameCeiling()}: the ceiling has to be
     * high enough that a plain JVM can boot and complete one round trip inside it, or "the child
     * died" proves only that the JVM could not start -- which is not containment of a runaway
     * plugin. Both tests use this same value, and the well-behaved one is what pins it honest.
     */
    private static final int MEMORY_CEILING_MB = 256;
    private static final int CPU_RATE_PERCENT = 50;

    private final ExecutorService boundedWait = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        boundedWait.shutdownNow();
    }

    /**
     * Fails (does NOT skip) when the limiter reports no available mechanism. A skip here would be
     * the silent-pass this whole item exists to remove: the run was asked for a Linux proof, so a
     * host that cannot give one is a failed setup, and the message says exactly which flag is
     * missing.
     */
    private static void assumeARealCeilingCanBeApplied() {
        PluginProcessResourceLimiter limiter = PluginProcessResourceLimiter.forCurrentOs();
        assertTrue(limiter.isAvailable(),
                () -> "no Linux resource-limiting mechanism is available in this environment, so nothing here "
                        + "can prove containment. In Docker this means the container was started without "
                        + "--privileged --cgroupns=private, or without "
                        + "scripts/quality/linux-plugin-proof/cgroup-delegate-init.sh having run first. "
                        + "Limiter picked: " + limiter.getClass().getSimpleName());
    }

    /** The discriminator: a well-behaved plugin must COMPLETE under the very same ceiling. */
    @Test
    void aWellBehavedChildSurvivesTheSameCeiling() throws Exception {
        assumeARealCeilingCanBeApplied();
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);
        PluginProcessResourceLimits limits = new PluginProcessResourceLimits(MEMORY_CEILING_MB, CPU_RATE_PERCENT);

        try (PluginIpcChildProcess child = PluginIpcChildProcess.start(
                AuditLogEchoPluginHandler.class.getName(), System.getProperty("java.class.path"), limits)) {
            CapabilityResult result = child.invoke(hostSession, auditLogContribution(), appendCall(), Map.of());

            assertTrue(result.ok(), () -> "a well-behaved plugin must run to completion under a "
                    + MEMORY_CEILING_MB + "MB ceiling -- if this fails, the ceiling is killing the JVM at "
                    + "startup and the runaway test below proves nothing. Got: " + result);
            assertEquals(1, auditLogStore.records.size());
        }
    }

    @Test
    void aChildThatExceedsItsMemoryCeilingIsKilledByTheCgroup() throws Exception {
        assumeARealCeilingCanBeApplied();
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);
        PluginProcessResourceLimits limits = new PluginProcessResourceLimits(MEMORY_CEILING_MB, CPU_RATE_PERCENT);

        try (PluginIpcChildProcess child = PluginIpcChildProcess.start(
                MemoryHogPluginHandler.class.getName(), System.getProperty("java.class.path"), limits)) {
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
                AuditLogEchoPluginHandler.class.getName(), System.getProperty("java.class.path"),
                PluginProcessResourceLimits.NONE)) {
            CapabilityResult result = child.invoke(hostSession, auditLogContribution(), appendCall(), Map.of());

            assertTrue(result.ok(), () -> "expected success, got: " + result);
            assertEquals(1, auditLogStore.records.size());
        }
    }

    private CapabilityResult invokeWithBoundedWait(PluginIpcChildProcess child, PluginIpcHostSession hostSession)
            throws Exception {
        Future<CapabilityResult> future = boundedWait.submit(
                () -> child.invoke(hostSession, auditLogContribution(), appendCall(), Map.of()));
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            fail("child process was not killed by its configured memory ceiling within 30s -- "
                    + "the cgroup limit did not take effect");
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
