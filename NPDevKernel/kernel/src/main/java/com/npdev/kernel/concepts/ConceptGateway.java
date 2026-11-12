package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;

import java.util.List;
import java.util.Optional;

public interface ConceptGateway {
    Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context);

    List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context);

    /**
     * LNCH-5: tenant- and permission-enforced paged query (filter/sort/page) returning a
     * {@link ConceptPage}. The default evaluates the query in memory over {@link #list} results, so
     * any gateway gets correct behaviour for free; {@code DefaultConceptGateway} overrides it to push
     * the window down to the {@code ConceptStore} (and thus to SQL) instead of materializing every
     * row. Same isolation and field-visibility guarantees as {@code list}.
     */
    default ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
        List<ConceptRecord> all = list(
                new ConceptListRequest(request.conceptName(), request.tenantId(), null, null), context);
        return ConceptQueryEngine.apply(all, request.query());
    }

    /**
     * RUN-1 (R8a): capped counterpart of {@link #list} -- applies the SAME tenant/permission/
     * row-level enforcement as {@link #list}, but never returns more than {@code maxRows} records
     * and reports (via {@link ConceptListSlice#truncated()}) whether more existed. The default
     * evaluates {@link #list} in memory and trims -- exactly mirroring {@link #query}'s own default
     * immediately above -- so any gateway gets correct (if not maximally efficient) behaviour for
     * free; {@code DefaultConceptGateway} overrides it to push the cap down to
     * {@link com.npdev.kernel.ports.ConceptStore#findAllCapped} instead of materializing the whole
     * table first.
     */
    default ConceptListSlice<ConceptRecord> listCapped(ConceptListRequest request, ExecutionContext context, int maxRows) {
        List<ConceptRecord> all = list(request, context);
        if (all.size() <= maxRows) {
            return new ConceptListSlice<>(all, false);
        }
        return new ConceptListSlice<>(all.subList(0, maxRows), true);
    }

    /**
     * Move 10 B1 (LC-B1): tenant- and permission-enforced grouped/aggregated query. The default
     * evaluates it in memory over {@link #list} results -- since {@code list} already applies
     * whatever row-level scoping the implementing gateway enforces BEFORE this aggregates over the
     * result, this default is safe even for a concept declaring {@code access.read}.
     * {@code DefaultConceptGateway} overrides this to push the aggregation down to the
     * {@code ConceptStore} (and thus to SQL) for performance -- see ITS OWN javadoc for why that
     * override must refuse an {@code access.read} concept instead of aggregating unscoped rows.
     */
    default ConceptAggregateResult aggregate(ConceptAggregateRequest request, ExecutionContext context) {
        List<ConceptRecord> all = list(
                new ConceptListRequest(request.conceptName(), request.tenantId(), null, null), context);
        return ConceptAggregateEngine.apply(all, request.query());
    }

    ConceptRecord save(ConceptWriteRequest request, ExecutionContext context);

    /**
     * REG-267: runs {@code work} (typically several {@link #save}/delete calls) as one all-or-nothing
     * unit: if it throws, every write this gateway made from the calling thread inside it is undone
     * where the backing store can undo it. The default is a passthrough (no atomicity promise);
     * {@code DefaultConceptGateway} overrides it. Callers that own a real database transaction
     * (the aggregate runtime with a transaction manager) do not need it.
     */
    default <T> T runAtomically(java.util.function.Supplier<T> work) {
        return work.get();
    }

    /**
     * REG-16-resid Round 3 (R3-F2): answer "may this actor write this record?" WITHOUT writing it.
     *
     * <p>Runs exactly the two gates {@link #save} runs — {@code concept.write} permission and the
     * row-level {@code access.write} scope — against the record's CURRENT state, then stops. Throws
     * {@code ConceptGatewayAccessDeniedException} on denial, the same exception {@code save} throws,
     * so callers need no new error handling.</p>
     *
     * <p><b>Why this had to exist.</b> A generated app's many-to-many bond endpoints mutate a junction
     * table, not the concept row, so there is nothing meaningful to {@code save} — yet they must still
     * be gated by the source record's write authorization. Before this, the only way to ask the
     * question was to perform a write, so the endpoints asked nothing at all and shipped with zero
     * authorization. A check that can only be performed by causing the side effect is a check people
     * will skip.</p>
     *
     * <p>The default implementation is deliberately <b>fail-closed</b>: a gateway that has not
     * implemented the check denies rather than silently allowing. That is the opposite of the usual
     * default-method convention, and it is the right way round here — the failure mode of the
     * permissive default is exactly the bug this method was added to fix.</p>
     */
    default void authorizeWrite(ConceptReadRequest request, ExecutionContext context) {
        throw new ConceptGatewayAccessDeniedException(
                "AUTHORIZATION_UNAVAILABLE",
                "This ConceptGateway cannot authorize a write without performing it; denying "
                        + request.conceptName() + " rather than proceeding unchecked.");
    }

    /**
     * REG-120: the CREATE-time counterpart of {@link #authorizeWrite} -- answers "may this actor
     * create this row?" WITHOUT persisting it. Runs the same gates {@link #save} runs for a create
     * (the {@code concept.write} permission, the row-level {@code access.write} scope evaluated
     * against the SUPPLIED payload rather than a previous record, since none exists yet, and the
     * coarse invariant/lifecycle BEFORE_COMMIT rule profiles) and then stops.
     *
     * <p><b>Why this had to exist.</b> A concept whose create is delegated to a declared Flow still
     * needs LNCH-13/REG-16-resid R2's enforcement to run and be able to deny BEFORE the Flow's own
     * side effects run -- but the Flow's own {@code createConcept} step is that row's one
     * authoritative writer. Before this method existed, the only way to run that enforcement was
     * {@link #save}, which ALSO persists -- so a Flow-backed create was persisted once by this call
     * and again by the Flow's own (separate transaction/connection) write, the two racing for the
     * same row (REG-120: reproduced live as an H2 "Concurrent update" error on every such create).
     *
     * <p>The default implementation is deliberately <b>fail-closed</b>, matching
     * {@link #authorizeWrite}'s own convention: a gateway that has not implemented the check denies
     * rather than silently allowing.
     */
    default void authorizeCreate(ConceptWriteRequest request, ExecutionContext context) {
        throw new ConceptGatewayAccessDeniedException(
                "AUTHORIZATION_UNAVAILABLE",
                "This ConceptGateway cannot authorize a create without performing it; denying "
                        + request.conceptName() + " rather than proceeding unchecked.");
    }

    /**
     * REG-338 / REG-330: the {@code access.write} scope checks a flow's {@code createConcept} /
     * {@code updateConcept} step runs as the flow's actor: the ROW rule, then the FIELD rules. A
     * flow's step reaches persistence through the capability adapter, never through {@link #save}, so
     * without this the concept's write rules (e.g. leave's {@code employeeId == $user.id}, or a field
     * only a dispatcher may write) guarded REST and were not consulted for a flow, leaving
     * {@code flow.execute} as the only gate.
     *
     * <p>Deliberately narrower than {@link #authorizeCreate}: no {@code concept.write} permission
     * (a flow may legitimately write rows its caller has no REST write permission on -- that is what
     * the flow's own {@code flow.execute} role gate is for) and no semantic checks (defaults,
     * lifecycle, rule profiles). The row rule runs against the supplied payload for a create, and
     * against the stored row plus the row as it will be written for an update; the field rules look
     * only at fields whose value the step changes -- exactly the evaluation {@link #save} makes. An
     * update of an id with no stored row checks nothing here; the step's own update path reports it
     * missing. A write that must touch what its caller may not goes through a {@code runAs: "system"}
     * procedure.
     *
     * <p>Fail-closed by default, matching {@link #authorizeWrite}.
     *
     * @param id the row id for an update; {@code null} for a create that has no id yet
     */
    default void authorizeFlowWrite(
            String conceptName,
            String id,
            java.util.Map<String, Object> data,
            boolean update,
            ExecutionContext context
    ) {
        throw new ConceptGatewayAccessDeniedException(
                "AUTHORIZATION_UNAVAILABLE",
                "This ConceptGateway cannot authorize a flow write; denying "
                        + conceptName + " rather than proceeding unchecked.");
    }

    void delete(ConceptReadRequest request, ExecutionContext context);

    default List<ConceptGatewayTraceRecord> explain() {
        return List.of();
    }
}
