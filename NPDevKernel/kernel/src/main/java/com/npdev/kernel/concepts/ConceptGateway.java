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

    void delete(ConceptReadRequest request, ExecutionContext context);

    default List<ConceptGatewayTraceRecord> explain() {
        return List.of();
    }
}
