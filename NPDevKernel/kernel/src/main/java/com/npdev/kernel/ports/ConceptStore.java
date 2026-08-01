package com.npdev.kernel.ports;

import com.npdev.kernel.concepts.ConceptAggregateEngine;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryEngine;
import com.npdev.kernel.concepts.ConceptRecord;

import java.util.List;
import java.util.Optional;

public interface ConceptStore {
    Optional<ConceptRecord> findById(String tenantId, String conceptName, String id);

    /**
     * B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): same as {@link #findById}, but under a
     * pessimistic row lock when the underlying store supports one (a database-backed store issues
     * {@code SELECT ... FOR UPDATE}). Used by the write path ({@code DefaultConceptGateway.save}/
     * {@code delete}) to close the check-then-act race between reading a row's current state (what
     * {@code isRowWritable} evaluates) and persisting a write based on it: a concurrent writer's own
     * {@code findByIdForUpdate} against the same row blocks until this caller's transaction commits
     * or rolls back.
     *
     * <p>The lock only actually holds anything when the caller is inside a real transaction (see
     * {@link com.npdev.kernel.ports.TransactionRunner}) -- this default implementation is a plain,
     * unlocked {@link #findById}, correct wherever there is no concurrent-transaction concern
     * (in-proc/in-memory stores, or any store used outside a real transaction). A database-backed
     * store (see {@code JdbcBusinessConceptStore}) overrides this with a real locking read.
     */
    default Optional<ConceptRecord> findByIdForUpdate(String tenantId, String conceptName, String id) {
        return findById(tenantId, conceptName, id);
    }

    List<ConceptRecord> findAll(String tenantId, String conceptName);

    /**
     * LNCH-16: {@code record.rowVersion() == null} is an unconditional write (create, or an
     * explicit force-update) -- implementations still track/increment a stored version so a later
     * caller can compare-and-swap against it. A non-null {@code rowVersion} is a compare-and-swap
     * request: it must match what is currently stored, or the implementation throws
     * {@link com.npdev.kernel.concepts.ConceptStoreOptimisticLockException} with the current record
     * attached. On success the returned record's {@code rowVersion} is the new (incremented) value.
     */
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

    /**
     * Move 10 B1 (LC-B1): tenant-scoped, filtered, grouped, aggregated query. The default fetches
     * the tenant's rows and evaluates the contract in memory via {@link ConceptAggregateEngine} --
     * correct for the {@code *-inproc} adapters. A database-backed store (see
     * {@code JdbcBusinessConceptStore}) overrides this to compile the query to a real SQL
     * {@code GROUP BY}, so a large table's aggregation runs in the database, not the JVM.
     *
     * <p>Callers MUST refuse this for a concept declaring {@code access.read} before calling it --
     * see {@code DefaultConceptGateway#aggregate}'s own javadoc for why a pushed-down GROUP BY
     * cannot honor row-level read scope the way {@link #query} can (its per-row filter runs AFTER
     * the fetch; a computed group total has already leaked by then). This port method itself does
     * not know about {@code access.read} -- the refusal is the gateway's job, same layering as
     * every other row-level enforcement in this codebase.
     */
    default ConceptAggregateResult aggregate(String tenantId, String conceptName, ConceptAggregateQuery query) {
        return ConceptAggregateEngine.apply(findAll(tenantId, conceptName), query);
    }
}
