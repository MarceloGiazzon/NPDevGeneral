package com.npdev.kernel.ports;

import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryEngine;
import com.npdev.kernel.concepts.ConceptRecord;

import java.util.List;
import java.util.Optional;

public interface ConceptStore {
    Optional<ConceptRecord> findById(String tenantId, String conceptName, String id);

    List<ConceptRecord> findAll(String tenantId, String conceptName);

    ConceptRecord save(ConceptRecord record);

    void deleteById(String tenantId, String conceptName, String id);

    /**
     * LNCH-5: tenant-scoped, filtered, sorted, paged query. The default fetches the tenant's rows and
     * evaluates the contract in memory via {@link ConceptQueryEngine} -- correct for the {@code
     * *-inproc} adapters and any store that has not (yet) pushed the query to its backend. A
     * database-backed store (see {@code JdbcBusinessConceptStore}) overrides this to compile the
     * query to parameterized SQL with LIMIT/OFFSET, so large tables never stream every row through
     * the JVM.
     */
    default ConceptPage query(String tenantId, String conceptName, ConceptQuery query) {
        return ConceptQueryEngine.apply(findAll(tenantId, conceptName), query);
    }
}
