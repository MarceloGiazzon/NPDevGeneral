package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.ConceptStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-210 (ledger item, boundary B18, POSTURAL_LIFT_PLAN_2026-09-07.md package P2): under
 * {@code TransactionRunner.none()} there is no transaction manager and no lock to take -- but the
 * gateway still holds the row version it just read, and can compare-and-swap against it, turning a
 * lost write-write race into a loud {@link ConceptStoreOptimisticLockException} instead of a silent
 * overwrite. Proved RED against unmodified code: today the second writer silently wins on BOTH save
 * and delete.
 *
 * <p>The race is forced deterministically with a store whose read-for-update holds both caller
 * threads at a barrier until BOTH have read the same version -- a store-level primitive for the
 * write-write window REG-210 closes, the same determinism technique
 * {@code ReassigningAfterReadStore} uses in {@link DefaultConceptGatewayRowAuthzRaceTest}.
 */
class DefaultConceptGatewayDegradedConcurrencyTest {

    /** {@code InMemoryConceptStore} is final, so the barrier wraps one (compose, don't extend).
     *  The barrier engages ONLY on {@link #findByIdForUpdate} -- exactly the read the gateway's
     *  write path uses -- so test seeding goes through {@link #seed}, straight to the backing
     *  store, never through the barrier. */
    private static final class ReadBarrierStore implements ConceptStore {
        private final InMemoryConceptStore delegate = new InMemoryConceptStore();
        private final CountDownLatch bothRead = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);

        ConceptRecord seed(ConceptRecord record) {
            return delegate.save(record);
        }

        @Override
        public Optional<ConceptRecord> findByIdForUpdate(String tenantId, String conceptName, String id) {
            bothRead.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return delegate.findById(tenantId, conceptName, id);
        }

        @Override
        public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
            return delegate.findById(tenantId, conceptName, id);
        }

        @Override
        public List<ConceptRecord> findAll(String tenantId, String conceptName) {
            return delegate.findAll(tenantId, conceptName);
        }

        @Override
        public ConceptRecord save(ConceptRecord record) {
            return delegate.save(record);
        }

        @Override
        public void deleteById(String tenantId, String conceptName, String id) {
            delegate.deleteById(tenantId, conceptName, id);
        }

        @Override
        public void deleteById(String tenantId, String conceptName, String id, Long expectedRowVersion) {
            // MUST forward the 4-arg form: the port default would silently downgrade the CAS to
            // today's version-less delete, and the whole point of this test would disappear.
            delegate.deleteById(tenantId, conceptName, id, expectedRowVersion);
        }

        void waitUntilBothRead() throws InterruptedException {
            assertTrue(bothRead.await(5, TimeUnit.SECONDS), "both callers must reach the read barrier");
        }

        void releaseReaders() {
            release.countDown();
        }
    }

    @Test
    void degradedModeSaveRaceIsLoudNotSilent() throws Exception {
        ReadBarrierStore store = new ReadBarrierStore();
        DefaultConceptGateway gateway = new DefaultConceptGateway(store);
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a");
        store.seed(new ConceptRecord("UserAccount", "user-1", "tenant-a", Map.of("email", "start@example.test")));

        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();
        Thread threadA = new Thread(() -> saveRacing(gateway, context, go, failureA), "degraded-save-a");
        Thread threadB = new Thread(() -> saveRacing(gateway, context, go, failureB), "degraded-save-b");
        threadA.start();
        threadB.start();
        go.countDown();
        store.waitUntilBothRead();
        store.releaseReaders();
        threadA.join(TimeUnit.SECONDS.toMillis(5));
        threadB.join(TimeUnit.SECONDS.toMillis(5));

        // Exactly one writer loses, loudly. Today (pre-REG-210) BOTH succeed and the last one
        // silently overwrites the other -- no exception at all.
        assertTrue(failureA.get() != null ^ failureB.get() != null,
                "exactly one degraded-mode save must lose the race; got A=" + failureA.get() + " B=" + failureB.get());
        Throwable loser = failureA.get() != null ? failureA.get() : failureB.get();
        assertInstanceOf(ConceptGatewayOptimisticLockException.class, loser,
                "the loser is told, loudly, not overwritten silently: " + loser);
        assertEquals("late@example.test",
                store.findById("tenant-a", "UserAccount", "user-1").orElseThrow().data().get("email"),
                "the winner's write is what persists");
    }

    private void saveRacing(DefaultConceptGateway gateway, ExecutionContext context, CountDownLatch go,
                            AtomicReference<Throwable> failureRef) {
        try {
            go.await();
            gateway.save(new ConceptWriteRequest("UserAccount", "user-1", "tenant-a",
                    Map.of("email", "late@example.test"), null, false), context);
        } catch (Throwable t) {
            failureRef.set(t);
        }
    }

    @Test
    void degradedModeDeleteRaceIsLoudNotSilent() throws Exception {
        ReadBarrierStore store = new ReadBarrierStore();
        DefaultConceptGateway gateway = new DefaultConceptGateway(store);
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a");
        store.seed(new ConceptRecord("UserAccount", "user-1", "tenant-a", Map.of("email", "start@example.test")));

        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();
        Thread threadA = new Thread(() -> deleteRacing(gateway, context, go, failureA), "degraded-delete-a");
        Thread threadB = new Thread(() -> deleteRacing(gateway, context, go, failureB), "degraded-delete-b");
        threadA.start();
        threadB.start();
        go.countDown();
        store.waitUntilBothRead();
        store.releaseReaders();
        threadA.join(TimeUnit.SECONDS.toMillis(5));
        threadB.join(TimeUnit.SECONDS.toMillis(5));

        // Exactly one deleter wins; the loser is told the row no longer carries the version it
        // read -- not silently a no-op. Today (pre-REG-210) BOTH deletes succeed silently.
        assertTrue(failureA.get() != null ^ failureB.get() != null,
                "exactly one degraded-mode delete must lose the race; got A=" + failureA.get() + " B=" + failureB.get());
        Throwable loser = failureA.get() != null ? failureA.get() : failureB.get();
        assertInstanceOf(ConceptStoreOptimisticLockException.class, loser,
                "the losing delete is a loud CAS conflict, not a silent no-op: " + loser);
        assertTrue(store.findById("tenant-a", "UserAccount", "user-1").isEmpty(),
                "the winner's delete is what persisted");
    }

    private void deleteRacing(DefaultConceptGateway gateway, ExecutionContext context, CountDownLatch go,
                              AtomicReference<Throwable> failureRef) {
        try {
            go.await();
            gateway.delete(new ConceptReadRequest("UserAccount", "user-1", "tenant-a"), context);
        } catch (Throwable t) {
            failureRef.set(t);
        }
    }
}