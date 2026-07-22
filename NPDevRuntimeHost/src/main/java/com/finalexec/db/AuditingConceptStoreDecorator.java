package com.finalexec.db;

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

    @Override
    public List<ConceptRecord> findAll(String tenantId, String conceptName) {
        log("findAll", "");
        return delegate.findAll(tenantId, conceptName);
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

    private void log(String operation, String id) {
        System.out.println("NPDev persistence adapter override [persistence-audited] concept=" + conceptName
                + " op=" + operation + (id == null || id.isBlank() ? "" : " id=" + id));
    }
}
