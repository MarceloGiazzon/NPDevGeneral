package com.finalexec.live;

import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptListSlice;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * FORM P5-T5: publishes "row changed" to the {@link LiveChangeHub} after every successful write made through the
 * application's ONE {@link ConceptStore} bean -- the funnel generated CRUD, flow {@code createConcept}/{@code updateConcept}
 * steps, aggregate commits and agent writes all end in, the same one {@code RollupConceptStoreDecorator} wraps. It sits
 * INSIDE that decorator, so the parent-row rollup recomputations ({@link #writeMaintainedFields}) are announced too.
 *
 * <p>Transaction semantics: when a Spring transaction synchronization is active the notice is registered for
 * {@code afterCommit} -- a rolled-back write publishes nothing, and a reader that reacts to the notice always sees the
 * committed row. With no synchronization (autocommit JDBC, the in-process store) the write is already durable when the
 * store call returns, so the notice goes out right after it. The in-process store's {@code runAtomically} undo journal
 * has no commit hook: a write undone by it was announced already and the client's refetch simply finds the old row (a
 * spurious notice, never a missed one).
 *
 * <p>Publishing never fails or slows the write: every publish is wrapped, and the hub itself only enqueues.
 */
public final class LiveChangeStoreDecorator implements ConceptStore {

    private static final Logger LOG = LoggerFactory.getLogger(LiveChangeStoreDecorator.class);

    private final ConceptStore delegate;
    private final LiveChangeHub hub;

    public LiveChangeStoreDecorator(ConceptStore delegate, LiveChangeHub hub) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.hub = Objects.requireNonNull(hub, "hub");
    }

    @Override
    public ConceptRecord save(ConceptRecord record) {
        ConceptRecord saved = delegate.save(record);
        ConceptRecord announced = saved != null ? saved : record;
        if (announced != null) {
            announce(announced.tenantId(), announced.conceptName(), announced.id(), announced.rowVersion());
        }
        return saved;
    }

    @Override
    public void deleteById(String tenantId, String conceptName, String id) {
        delegate.deleteById(tenantId, conceptName, id);
        announce(tenantId, conceptName, id, null);
    }

    @Override
    public void deleteById(String tenantId, String conceptName, String id, Long expectedRowVersion) {
        delegate.deleteById(tenantId, conceptName, id, expectedRowVersion);
        announce(tenantId, conceptName, id, null);
    }

    @Override
    public boolean restore(String tenantId, String conceptName, String id) {
        boolean restored = delegate.restore(tenantId, conceptName, id);
        if (restored) {
            announce(tenantId, conceptName, id, null);
        }
        return restored;
    }

    @Override
    public void writeMaintainedFields(String tenantId, String conceptName, String id, Map<String, Object> values) {
        delegate.writeMaintainedFields(tenantId, conceptName, id, values);
        if (values != null && !values.isEmpty()) {
            announce(tenantId, conceptName, id, null);
        }
    }

    @Override
    public <T> T runAtomically(Supplier<T> work) {
        return delegate.runAtomically(work);
    }

    @Override
    public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
        return delegate.findById(tenantId, conceptName, id);
    }

    @Override
    public Optional<ConceptRecord> findByIdForUpdate(String tenantId, String conceptName, String id) {
        return delegate.findByIdForUpdate(tenantId, conceptName, id);
    }

    @Override
    public List<ConceptRecord> findAll(String tenantId, String conceptName) {
        return delegate.findAll(tenantId, conceptName);
    }

    @Override
    public ConceptListSlice<ConceptRecord> findAllCapped(String tenantId, String conceptName, int maxRows) {
        return delegate.findAllCapped(tenantId, conceptName, maxRows);
    }

    @Override
    public ConceptPage query(String tenantId, String conceptName, ConceptQuery query) {
        return delegate.query(tenantId, conceptName, query);
    }

    @Override
    public ConceptAggregateResult aggregate(String tenantId, String conceptName, ConceptAggregateQuery query) {
        return delegate.aggregate(tenantId, conceptName, query);
    }

    @Override
    public boolean existsUnique(String tenantId, String conceptName, List<String> fieldNames, List<Object> values, String excludeId) {
        return delegate.existsUnique(tenantId, conceptName, fieldNames, values, excludeId);
    }

    private void announce(String tenantId, String conceptName, String id, Long version) {
        try {
            Runnable publish = () -> hub.publish(tenantId, conceptName, id, version);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publish.run();
                    }
                });
            } else {
                publish.run();
            }
        } catch (Throwable problem) {
            LOG.debug("live notice for {} {} skipped: {}", conceptName, id, problem.toString());
        }
    }
}
