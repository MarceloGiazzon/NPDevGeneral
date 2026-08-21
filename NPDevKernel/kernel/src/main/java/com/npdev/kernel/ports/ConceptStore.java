package com.npdev.kernel.ports;

import com.npdev.kernel.concepts.ConceptAggregateEngine;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptListSlice;
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

    /**
     * R5.4: physically removes the row -- UNCHANGED contract, still what every caller gets for a
     * concept that does not declare {@code softDelete: true}. For a concept that DOES, a
     * schema-aware store (see {@code JdbcBusinessConceptStore}) overrides this to flip a
     * {@code deletedAt} timestamp instead of removing anything, and every read method on that same
     * store (findById/findAllCapped/query/aggregate/existsUnique) then excludes the row -- so this
     * port method's signature and physical-delete javadoc stay exactly as they always were; the
     * soft-delete behavior is entirely an override's business, invisible at this interface.
     */
    void deleteById(String tenantId, String conceptName, String id);

    /**
     * R5.4: the restore half of soft delete -- clears whatever {@link #deleteById} set, making the
     * row visible to every read method again. Default {@code false} (a no-op) is deliberately the
     * answer for any store with no schema knowledge of which concepts declare {@code softDelete} --
     * unlike {@link #existsUnique}, this is not something a schema-agnostic default can approximate
     * correctly (it would need to know a "deletedAt" convention this port has no opinion on), so
     * "not supported here" is the honest default rather than a guess. A schema-aware store overrides
     * this with a real implementation; {@code AuditingConceptStoreDecorator}/
     * {@code TenantControlledConceptStoreDecorator} forward to whatever they wrap (same pattern R5.2/
     * RUN-16 established for {@link #existsUnique} -- without a forwarding override, wrapping a
     * concept in either decorator would silently downgrade restore back to this no-op default).
     *
     * @return true if a soft-deleted row was found and restored; false if unsupported, the row does
     *         not exist, or the row was already live
     */
    default boolean restore(String tenantId, String conceptName, String id) {
        return false;
    }

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

    /**
     * RUN-1 (R8a): a hard ceiling on a single list()-shaped read, so a caller that only needs a
     * bounded response -- generated CRUD's REST {@code list()} surface in particular -- never
     * forces an entire tenant table into the JVM. {@link #findAll} keeps its existing unbounded
     * contract for a caller that genuinely needs every row (a flow's {@code forEach} step, a
     * reconciliation job); this is for a caller that serves a response and can signal truncation
     * ({@link com.npdev.kernel.concepts.ConceptListSlice#truncated()}) instead of paying for
     * completeness.
     *
     * <p>The default implementation still calls {@link #findAll} and trims in the JVM -- correct,
     * and memory-safe for the CALLER, on the {@code *-inproc} adapters this default targets (same
     * "correct for in-proc, real pushdown on JDBC" shape as {@link #query}/{@link #aggregate}
     * above). A database-backed store (see {@code JdbcBusinessConceptStore}) overrides this to push
     * a {@code LIMIT} into SQL, so the database itself never streams more than {@code maxRows + 1}
     * rows for a single request.
     */
    default ConceptListSlice<ConceptRecord> findAllCapped(String tenantId, String conceptName, int maxRows) {
        List<ConceptRecord> all = findAll(tenantId, conceptName);
        if (all.size() <= maxRows) {
            return new ConceptListSlice<>(all, false);
        }
        return new ConceptListSlice<>(all.subList(0, maxRows), true);
    }

    /**
     * R5.2 (closes RUN-1 item 4): true when some OTHER row (tenant-scoped, {@code excludeId}
     * excluded) already carries value(s) that COLLIDE with {@code values} under the exact rule
     * service-base.mustache's generated {@code existsUniqueInConceptStore}/
     * {@code existsUniqueCompoundInConceptStore} have always enforced -- see
     * {@link #uniqueValuesCollide} for the pinned per-field comparison. {@code fieldNames.size()==1}
     * is the plain single-field check; 2+ is the compound (AND-across-fields) check LIFT-UNIQUE-P3
     * added. A null in {@code values} can never collide (matches {@link #uniqueValuesCollide}'s own
     * null rule), so this returns {@code false} immediately without touching storage when any is
     * present.
     *
     * <p>The default implementation below is the exact pre-R5.2 behavior, still calling
     * {@link #findAll} -- correct, and the only option, for the in-proc adapters (same "correct for
     * in-proc, real pushdown on JDBC" shape as {@link #query}/{@link #aggregate}/
     * {@link #findAllCapped} above). Before R5.2 this loop lived duplicated inside EVERY generated
     * {@code ...ServiceBase}, calling {@link #findAll} directly and materializing an entire tenant
     * table into the JVM for every single create/update -- the platform's worst remaining data-scale
     * landmine (ledger RUN-1 item 4). A database-backed store (see {@code JdbcBusinessConceptStore})
     * overrides this to push a candidate-narrowing {@code WHERE} down to SQL (reusing the engine's
     * own pre-existing unique index for every non-text field type), so the database -- not the JVM --
     * does the elimination; the JVM only re-confirms the (typically zero or one) candidate rows SQL
     * returns, via this exact same {@link #uniqueValuesCollide}, so the answer is byte-for-byte
     * identical to the old full-scan for every input, not merely "usually" identical.
     */
    default boolean existsUnique(String tenantId, String conceptName, List<String> fieldNames, List<Object> values, String excludeId) {
        if (fieldNames == null || fieldNames.isEmpty() || values == null || fieldNames.size() != values.size()) {
            return false;
        }
        for (Object value : values) {
            if (value == null) {
                return false;
            }
        }
        for (ConceptRecord record : findAll(tenantId, conceptName)) {
            if (excludeId != null && excludeId.equalsIgnoreCase(record.id())) {
                continue;
            }
            boolean allMatch = true;
            for (int i = 0; i < fieldNames.size(); i++) {
                Object existingValue = record.data().get(fieldNames.get(i));
                if (!uniqueValuesCollide(existingValue, values.get(i))) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return true;
            }
        }
        return false;
    }

    /**
     * R5.2: THE pinned uniqueness-collision rule -- byte-for-byte the algorithm
     * service-base.mustache's private {@code uniqueValuesEqual} has used since LIFT-UNIQUE-P3,
     * copied here (not shared by reference -- one is per-concept generated code, the other runtime
     * kernel code with no dependency between them) so both this port's default {@link #existsUnique}
     * and {@code JdbcBusinessConceptStore}'s SQL-pushdown override can call ONE canonical
     * implementation rather than each re-deriving it.
     *
     * <ul>
     *   <li>Either side {@code null} -&gt; never collides (a null-valued unique field never conflicts
     *       with anything, including another null).</li>
     *   <li>Either side a {@link String} -&gt; both sides compared as {@code String.valueOf(...)},
     *       trimmed, case-insensitively. This is what lets a numeric field submitted as a JSON string
     *       via the untyped {@code create(Map)} path still collide with the DB's numeric-typed stored
     *       value -- documented, relied-upon cross-type behavior, not an oversight.</li>
     *   <li>Neither side a {@link String} -&gt; {@link Object#equals} OR {@code String.valueOf}
     *       equality (handles same-value cross-numeric-wrapper mismatches, e.g. {@code Integer(5)}
     *       vs {@code Long(5)}, which {@code equals} alone treats as unequal).</li>
     * </ul>
     */
    static boolean uniqueValuesCollide(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof String || right instanceof String) {
            return String.valueOf(left).trim().equalsIgnoreCase(String.valueOf(right).trim());
        }
        return left.equals(right) || String.valueOf(left).equals(String.valueOf(right));
    }
}
