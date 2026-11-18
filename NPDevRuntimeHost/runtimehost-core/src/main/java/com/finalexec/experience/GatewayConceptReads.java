package com.finalexec.experience;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateRequest;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryPredicateCompiler;
import com.npdev.kernel.concepts.ConceptQueryRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * FORM P5-T4: {@link ExperienceEvaluator.ConceptReads} over the caller's own {@link ConceptGateway} context -- the same
 * door {@code GET /api/concepts/{concept}/page} uses, so concept permission ({@code concept.list}), tenant isolation,
 * field visibility and row-level {@code access.read} apply to a badge or a tile exactly as they apply to the list
 * behind it. Nothing here runs as the system.
 *
 * <ul>
 *   <li>{@code count} is {@link ConceptGateway#query} for one row, read as {@link ConceptPage#total()}. For a concept
 *       that declares {@code access.read} the gateway re-counts only the rows the caller may read (REG-42), so the
 *       number never leaks rows outside their scope.</li>
 *   <li>{@code sum}/{@code avg} are {@link ConceptGateway#aggregate} with no grouping. The gateway scopes it too: it
 *       pushes the caller's row scope into the aggregate's WHERE, or refuses a scope it cannot translate (the
 *       exception becomes a {@code null} upstream).</li>
 *   <li>The {@code where} text goes through {@link ConceptQueryPredicateCompiler} with the caller bound, so
 *       {@code $user.id} means this caller; a predicate outside the pushable grammar throws and answers null.</li>
 * </ul>
 */
public final class GatewayConceptReads implements ExperienceEvaluator.ConceptReads {

    private final ConceptGateway gateway;
    private final ExecutionContext context;

    public GatewayConceptReads(ConceptGateway gateway, ExecutionContext context) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public long count(String concept, String where) {
        ConceptQuery query = new ConceptQuery(filters(where), List.of(), 0, 1);
        return gateway.query(new ConceptQueryRequest(concept, context.tenantId(), query), context).total();
    }

    @Override
    public BigDecimal aggregate(String concept, String function, String field, String where) {
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                filters(where),
                List.of(),
                List.of(new ConceptAggregateQuery.AggregateFunction("value", function, field)),
                List.of(),
                List.of(),
                1);
        ConceptAggregateResult result = gateway.aggregate(
                new ConceptAggregateRequest(concept, context.tenantId(), query), context);
        if (result == null || result.rows().isEmpty()) {
            return null;
        }
        Object value = result.rows().get(0).get("value");
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal big ? big : new BigDecimal(value.toString());
    }

    private List<ConceptQuery.Filter> filters(String where) {
        if (where == null || where.isBlank()) {
            return List.of();
        }
        return ConceptQueryPredicateCompiler.compileToConceptQueryFilters(where, List.of(), Map.of(), context);
    }
}
