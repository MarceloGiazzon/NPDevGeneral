package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConceptAccess;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.ConceptStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): {@code DefaultConceptGateway.save}/
 * {@code delete} snapshot a row, evaluate {@code isRowWritable}, then persist later -- a race window
 * where a concurrent actor who ALREADY has legitimate write access could reassign ownership
 * mid-window. This documents the vulnerability with a deterministic, single-threaded simulation (a
 * {@link ConceptStore} decorator that performs the "reassign" write DIRECTLY after this save's own
 * read-for-update returns, before the gateway acts on it -- the same technique
 * {@code AggregateRuntimeCommitTransactionalTest}'s {@code FailingOnSaveGateway} already establishes
 * for deterministically forcing an otherwise timing-dependent scenario).
 *
 * <p>Proving the FIX (a real transaction + a real row lock closing this) needs a genuinely
 * concurrent, database-backed test -- kernel's in-memory store has no real locking primitive to
 * prove that against (its own {@code findByIdForUpdate} is the interface's plain, unlocked default).
 * See RuntimeHost's {@code DefaultConceptGatewayRowAuthzRaceTest} (real {@code JdbcBusinessConceptStore}
 * + a real H2 database + two real threads) for that half.
 */
class DefaultConceptGatewayRowAuthzRaceTest {

    private static CompiledModel ticketModel() {
        CompiledConcept ticket = new CompiledConcept(
                "Ticket", "Ticket", "tickets",
                List.of(
                        new CompiledField("id", "string", "String", true, true, false),
                        new CompiledField("ownerId", "string", "String", false, true, false),
                        new CompiledField("status", "string", "String", false, true, false)
                ),
                List.of(), List.of(), null, null, null, null, List.of(),
                new CompiledConceptAccess(null, "ownerId == $user.id")
        );
        return new CompiledModel("test", "1.0.0", "1.0.0", Map.of(ticket.getName(), ticket));
    }

    /**
     * Reassigns ownership DIRECTLY against the backing store (bypassing gateway authorization --
     * simulating a DIFFERENT actor who already legitimately holds write access) the instant the
     * wrapped save's own read-for-update returns, before the gateway acts on the (now stale)
     * decision that read produced. {@code findByIdForUpdate} is the hook because that is exactly
     * what {@link DefaultConceptGateway#save} calls to establish {@code isRowWritable}'s basis.
     */
    private static final class ReassigningAfterReadStore implements ConceptStore {
        private final ConceptStore delegate;
        private final Runnable reassign;
        private boolean fired = false;

        ReassigningAfterReadStore(ConceptStore delegate, Runnable reassign) {
            this.delegate = delegate;
            this.reassign = reassign;
        }

        @Override
        public Optional<ConceptRecord> findByIdForUpdate(String tenantId, String conceptName, String id) {
            Optional<ConceptRecord> snapshot = delegate.findByIdForUpdate(tenantId, conceptName, id);
            if (!fired) {
                fired = true;
                reassign.run();
            }
            return snapshot; // deliberately the STALE, pre-reassignment snapshot
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
    }

    @Test
    void todaysRaceLetsAStaleWriterActEvenAfterOwnershipWasReassignedMidWindow() {
        InMemoryConceptStore backing = new InMemoryConceptStore();
        backing.save(new ConceptRecord("Ticket", "T1", "tenant-a", Map.of("ownerId", "alice", "status", "Open")));

        ReassigningAfterReadStore store = new ReassigningAfterReadStore(backing, () ->
                backing.save(new ConceptRecord("Ticket", "T1", "tenant-a", Map.of("ownerId", "bob", "status", "Open"))));

        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(store, ticketModel());
        ExecutionContext asAlice = ExecutionContext.of("tenant-a", "alice");

        // Alice's own save reads (sees herself as owner, decides isRowWritable=true); the
        // reassignment to bob happens in the simulated gap above; alice's write STILL lands today,
        // using her now-stale authorization decision -- this is the documented, unfixed-at-this-layer
        // race. (TransactionRunner.none(), governedBy's default, changes nothing here: an in-memory
        // store has no lock for a transaction wrapper to hold in the first place.)
        ConceptRecord saved = gateway.save(
                new ConceptWriteRequest("Ticket", "T1", "tenant-a",
                        Map.of("id", "T1", "ownerId", "alice", "status", "InProgress"), null, false),
                asAlice);

        assertEquals("InProgress", saved.data().get("status"),
                "documents today's race: alice's write lands using her now-stale isRowWritable decision, "
                        + "even though bob was reassigned ownership before her write persisted");
    }
}
