package com.finalexec.db;

import com.npdev.kernel.concepts.ConceptListSlice;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;

import java.util.List;
import java.util.Optional;

/**
 * A second, real "persistence" adapter -- not a hypothetical one -- that proves the
 * personalization cascade's {@code persistence.adapter} override (see {@code NpdevSettings})
 * actually switches what runs, not just what's declared. Wraps the default {@link ConceptStore}
 * (today always {@link JdbcBusinessConceptStore}) and logs every access with a distinct, greppable
 * prefix before delegating -- same data, same correctness guarantees, observably different adapter
 * in effect. Selected per-concept at generation time via the cascade (see
 * {@code ServiceEmitter.persistenceAdapterOverride}), not a live per-request switch -- closing the
 * "hardcoded, no override" gap honestly scoped to what this generation-time mechanism can deliver.
 */
public final class AuditingConceptStoreDecorator implements ConceptStore {
    private final ConceptStore delegate;
    private final String conceptName;

    public AuditingConceptStoreDecorator(ConceptStore delegate, String conceptName) {
        this.delegate = delegate;
        this.conceptName = conceptName;
    }

    @Override
    public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
        log("findById", id);
        return delegate.findById(tenantId, conceptName, id);
    }

    /**
     * B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): forwards to the delegate's own real
     * locking read (on {@link JdbcBusinessConceptStore}, a {@code SELECT ... FOR UPDATE}) rather than
     * falling through to {@link ConceptStore}'s default (a plain, unlocked {@code findById}) -- the
     * same "without this override, wrapping a concept in this decorator silently downgrades a
     * capability" trap {@link #query} above already documents, now for the row lock the write path
     * relies on to close the check-then-act race.
     */
    @Override
    public Optional<ConceptRecord> findByIdForUpdate(String tenantId, String conceptName, String id) {
        log("findByIdForUpdate", id);
        return delegate.findByIdForUpdate(tenantId, conceptName, id);
    }

    @Override
    public List<ConceptRecord> findAll(String tenantId, String conceptName) {
        log("findAll", "");
        return delegate.findAll(tenantId, conceptName);
    }

    /**
     * RUN-1 (R8a): forwards to the delegate's own pushdown override (on
     * {@link JdbcBusinessConceptStore}, a real SQL {@code LIMIT}) rather than falling through to
     * {@link ConceptStore}'s default (fetch-all + trim-in-the-JVM) -- the same "without this
     * override, wrapping a concept in this decorator silently downgrades a capability" trap
     * {@link #query}/{@link #aggregate} below already document.
     */
    @Override
    public ConceptListSlice<ConceptRecord> findAllCapped(String tenantId, String conceptName, int maxRows) {
        log("findAllCapped", "");
        return delegate.findAllCapped(tenantId, conceptName, maxRows);
    }

    @Override
    public ConceptRecord save(ConceptRecord record) {
        log("save", record == null ? "" : record.id());
        return delegate.save(record);
    }

    @Override
    public void deleteById(String tenantId, String conceptName, String id) {
        log("deleteById", id);
        delegate.deleteById(tenantId, conceptName, id);
    }

    /**
     * LNCH-5: forwards to the delegate's own {@code query} override (the JDBC adapter's SQL
     * push-down) rather than falling through to {@link ConceptStore}'s default (fetch-all + in-memory
     * filter) -- without this override, wrapping a concept in this decorator would silently downgrade
     * every paged/filtered/sorted read for that concept back to the fetch-all path.
     */
    @Override
    public ConceptPage query(String tenantId, String conceptName, ConceptQuery query) {
        log("query", "");
        return delegate.query(tenantId, conceptName, query);
    }

    /**
     * Move 10 B1 (LC-B1): same reasoning as {@link #query} immediately above -- without this
     * override, wrapping a JDBC-backed store in this decorator would silently downgrade every
     * {@code groupBy}/aggregate query for this concept from a real SQL {@code GROUP BY} back to
     * fetch-all-then-aggregate-in-the-JVM ({@link ConceptStore}'s default).
     */
    @Override
    public com.npdev.kernel.concepts.ConceptAggregateResult aggregate(
            String tenantId, String conceptName, com.npdev.kernel.concepts.ConceptAggregateQuery query) {
        log("aggregate", "");
        return delegate.aggregate(tenantId, conceptName, query);
    }

    /**
     * R5.2 (RUN-1 item 4): forwards to the delegate's own pushdown override (on
     * {@link JdbcBusinessConceptStore}, a candidate-narrowing SQL {@code WHERE}) rather than falling
     * through to {@link ConceptStore}'s default (fetch-all + scan-in-the-JVM) -- the same
     * "without this override, wrapping a concept in this decorator silently downgrades a capability"
     * trap {@link #query}/{@link #aggregate} above already document.
     */
    @Override
    public boolean existsUnique(String tenantId, String conceptName, List<String> fieldNames, List<Object> values, String excludeId) {
        log("existsUnique", "");
        return delegate.existsUnique(tenantId, conceptName, fieldNames, values, excludeId);
    }

    private void log(String operation, String id) {
        System.out.println("NPDev persistence adapter override [persistence-audited] concept=" + conceptName
                + " op=" + operation + (id == null || id.isBlank() ? "" : " id=" + id));
    }
}
