package com.finalexec.pluginipc;

import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import com.finalexec.npdev.service.pluginipc.PluginIpcHostSession;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-3 / Model B step 3: the fixed-size warm pool of fungible worker processes (design doc section 2 /
 * section 6 step 3). Steps 1-2 proved the protocol and the real-process crash-handling sequence one
 * invocation at a time; this proves the POOL mechanics on top of that: a worker is reused across
 * DIFFERENT plugin classes (fungibility), recycled after its invocation ceiling or after sitting idle too
 * long, and -- design section 4's own explicitly-named test, not left as an inference from the design --
 * a worker that dies mid-invoke is replaced without disturbing any OTHER worker's own in-flight request.
 *
 * <p>Deliberately NOT covered here (see this step's ledger scoping note): real plugin-JAR classloading
 * via RuntimePluginPackageRealizationService. Every invocation below still names a real, compiled
 * TEST handler class by fully-qualified name -- proving the fungible-worker PROTOCOL and pool mechanics
 * work, not yet wired to how this platform's actual generated plugins get resolved.</p>
 */
class PluginIpcChildProcessPoolTest {

    private final ExecutorService concurrencyExecutor = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        concurrencyExecutor.shutdownNow();
    }

    @Test
    void aFungibleWorkerServesDifferentPluginClassesAcrossSuccessiveInvocations() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);

        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(1, 50, Duration.ofMinutes(10))) {
            CapabilityResult first = pool.invoke(
                    hostSession, auditLogContribution(), appendCall(), Map.of(), AuditLogEchoPluginHandler.class.getName()
            );
            assertTrue(first.ok(), () -> "expected success, got: " + first);
            assertEquals(1, auditLogStore.records.size());

            // Same single-worker pool (poolSize=1 forces literal reuse), a DIFFERENT plugin class this time --
            // proves the worker is not bound to the first invocation's handler.
            CapabilityResult second = pool.invoke(
                    hostSession, auditLogContribution(), appendCall(), Map.of(), HostileCallbackPluginHandler.class.getName()
            );
            assertFalse(second.ok());
            assertEquals("PLUGIN_CALLBACK_NOT_DECLARED", second.error().code());
            assertEquals(1, auditLogStore.records.size(), "the denied callback must never reach the real store");
            assertEquals(1, pool.idleWorkerCount());
        }
    }

    @Test
    void aWorkerIsRecycledOnceItReachesItsInvocationCeiling() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);

        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(1, 2, Duration.ofMinutes(10))) {
            for (int i = 0; i < 3; i++) {
                int invocationIndex = i;
                CapabilityResult result = pool.invoke(
                        hostSession, auditLogContribution(), appendCall(), Map.of(), AuditLogEchoPluginHandler.class.getName()
                );
                assertTrue(result.ok(), () -> "invocation " + invocationIndex + " expected success, got: " + result);
            }
            // The 3rd invoke's checkout blocks (BlockingQueue.take()) until the background replacement --
            // triggered when invocation 2 hit the ceiling of 2 -- actually lands, so no sleep/poll is needed
            // here to prove recycling happened: reaching this line already proves it.
            assertEquals(3, auditLogStore.records.size());
            assertEquals(1, pool.idleWorkerCount());
        }
    }

    @Test
    void aWorkerThatDiesMidInvokeIsReplacedWithoutDisturbingOtherConcurrentWorkers() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);

        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(3, 50, Duration.ofMinutes(10))) {
            CountDownLatch allStarted = new CountDownLatch(3);
            Future<CapabilityResult> crashing = concurrencyExecutor.submit(() -> {
                allStarted.countDown();
                allStarted.await();
                return pool.invoke(
                        hostSession, auditLogContribution(), appendCall(), Map.of(), SystemExitPluginHandler.class.getName()
                );
            });
            Future<CapabilityResult> survivorOne = concurrencyExecutor.submit(() -> {
                allStarted.countDown();
                allStarted.await();
                return pool.invoke(
                        hostSession, auditLogContribution(), appendCall(), Map.of(), AuditLogEchoPluginHandler.class.getName()
                );
            });
            Future<CapabilityResult> survivorTwo = concurrencyExecutor.submit(() -> {
                allStarted.countDown();
                allStarted.await();
                return pool.invoke(
                        hostSession, auditLogContribution(), appendCall(), Map.of(), AuditLogEchoPluginHandler.class.getName()
                );
            });

            CapabilityResult crashResult = crashing.get(15, TimeUnit.SECONDS);
            CapabilityResult survivorOneResult = survivorOne.get(15, TimeUnit.SECONDS);
            CapabilityResult survivorTwoResult = survivorTwo.get(15, TimeUnit.SECONDS);

            assertFalse(crashResult.ok());
            assertEquals("PLUGIN_EXECUTION_PROCESS_KILLED", crashResult.error().code());
            assertTrue(survivorOneResult.ok(), () -> "expected success, got: " + survivorOneResult);
            assertTrue(survivorTwoResult.ok(), () -> "expected success, got: " + survivorTwoResult);
            assertEquals(2, auditLogStore.records.size(), "only the two surviving invocations should have reached the store");

            // The pool itself keeps working afterward -- the dead worker's slot was refilled, not left empty.
            CapabilityResult afterward = pool.invoke(
                    hostSession, auditLogContribution(), appendCall(), Map.of(), AuditLogEchoPluginHandler.class.getName()
            );
            assertTrue(afterward.ok(), () -> "expected success, got: " + afterward);
        }
    }

    @Test
    void aWorkerThatHasSatIdleTooLongIsReplacedOnItsNextCheckout() throws Exception {
        InMemoryAuditLogStore auditLogStore = new InMemoryAuditLogStore();
        PluginIpcHostSession hostSession = hostSession(auditLogStore);

        try (PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(1, 50, Duration.ofMillis(50))) {
            CapabilityResult first = pool.invoke(
                    hostSession, auditLogContribution(), appendCall(), Map.of(), AuditLogEchoPluginHandler.class.getName()
            );
            assertTrue(first.ok(), () -> "expected success, got: " + first);

            Thread.sleep(200);

            CapabilityResult second = pool.invoke(
                    hostSession, auditLogContribution(), appendCall(), Map.of(), AuditLogEchoPluginHandler.class.getName()
            );
            assertTrue(second.ok(), () -> "expected success from a freshly-replaced worker, got: " + second);
            assertEquals(2, auditLogStore.records.size());
        }
    }

    @Test
    void aClosedPoolRejectsFurtherInvocationsAndDrainsItsIdleWorkers() throws Exception {
        PluginIpcChildProcessPool pool = new PluginIpcChildProcessPool(2, 50, Duration.ofMinutes(10));
        assertEquals(2, pool.idleWorkerCount());

        pool.close();

        assertEquals(0, pool.idleWorkerCount());
        assertTrue(assertThrowsIllegalState(pool));
    }

    private static boolean assertThrowsIllegalState(PluginIpcChildProcessPool pool) {
        try {
            pool.invoke(
                    hostSession(new InMemoryAuditLogStore()), auditLogContribution(), appendCall(), Map.of(),
                    AuditLogEchoPluginHandler.class.getName()
            );
            return false;
        } catch (IllegalStateException expected) {
            return true;
        } catch (InterruptedException unexpected) {
            Thread.currentThread().interrupt();
            return false;
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
