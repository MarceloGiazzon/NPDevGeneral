package com.finalexec.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** FORM P5-T5: the write funnel publishes after the write -- and, inside a transaction, only after the commit. */
class LiveChangeStoreDecoratorTest {

    private static final class Stub implements ConceptStore {
        boolean failWrites;

        @Override
        public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
            return Optional.empty();
        }

        @Override
        public List<ConceptRecord> findAll(String tenantId, String conceptName) {
            return List.of();
        }

        @Override
        public ConceptRecord save(ConceptRecord record) {
            if (failWrites) {
                throw new IllegalStateException("write failed");
            }
            return new ConceptRecord(record.conceptName(), record.id(), record.tenantId(), record.data(), 7L);
        }

        @Override
        public void deleteById(String tenantId, String conceptName, String id) {
            if (failWrites) {
                throw new IllegalStateException("delete failed");
            }
        }
    }

    private final List<String> seen = new ArrayList<>();
    private final LiveChangeHub hub = new LiveChangeHub(5, 16, 0, Runnable::run);
    private final Stub stub = new Stub();
    private final LiveChangeStoreDecorator store = new LiveChangeStoreDecorator(stub, hub);

    {
        hub.subscribe("default", "u", List.of("Ride"), new LiveChangeHub.Sink() {
            @Override
            public void changed(LiveChange change) {
                seen.add(change.concept() + ":" + change.id() + ":" + change.version());
            }

            @Override
            public void heartbeat() {
            }
        });
    }

    @AfterEach
    void clean() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static ConceptRecord ride(String id) {
        return new ConceptRecord("Ride", id, "default", Map.of("status", "REQUESTED"));
    }

    @Test
    void saveAnnouncesTheNewRowVersion_withoutATransaction() {
        ConceptRecord saved = store.save(ride("r1"));
        assertEquals(7L, saved.rowVersion());
        assertEquals(List.of("Ride:r1:7"), seen);
    }

    @Test
    void deleteAndRestoreAndMaintainedFieldsAreAnnounced() {
        store.deleteById("default", "Ride", "r2");
        store.deleteById("default", "Ride", "r3", 4L);
        store.writeMaintainedFields("default", "Ride", "r4", Map.of("n", 1));
        store.writeMaintainedFields("default", "Ride", "r5", Map.of()); // nothing written, nothing announced
        assertEquals(List.of("Ride:r2:null", "Ride:r3:null", "Ride:r4:null"), seen);
    }

    @Test
    void aFailedWriteAnnouncesNothing() {
        stub.failWrites = true;
        assertThrows(IllegalStateException.class, () -> store.save(ride("r1")));
        assertThrows(IllegalStateException.class, () -> store.deleteById("default", "Ride", "r1"));
        assertTrue(seen.isEmpty());
    }

    @Test
    void insideATransactionTheNoticeWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        store.save(ride("r1"));
        assertTrue(seen.isEmpty(), "nothing is published before the commit");
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertEquals(List.of("Ride:r1:7"), seen);
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        TransactionSynchronizationManager.initSynchronization();
        store.save(ride("r1"));
        store.deleteById("default", "Ride", "r2");
        // rollback: Spring calls afterCompletion(STATUS_ROLLED_BACK) and never afterCommit
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        assertTrue(seen.isEmpty());
    }

    @Test
    void aBrokenHubNeverFailsTheWrite() {
        LiveChangeHub exploding = new LiveChangeHub(5, 16, 0, Runnable::run);
        exploding.subscribe("default", "u", List.of("Ride"), new LiveChangeHub.Sink() {
            @Override
            public void changed(LiveChange change) throws Exception {
                throw new IllegalStateException("sink exploded");
            }

            @Override
            public void heartbeat() {
            }
        });
        ConceptRecord saved = new LiveChangeStoreDecorator(stub, exploding).save(ride("r1"));
        assertEquals("r1", saved.id());
    }
}
