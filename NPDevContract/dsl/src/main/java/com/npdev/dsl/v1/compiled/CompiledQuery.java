package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

public record CompiledQuery(
        String name,
        String concept,
        String where,
        List<String> orderBy,
        Integer limit,
        List<CompiledProcedureParameter> parameters,
        List<String> permissionRequirements,
        Map<String, Object> metadata,
        List<CompiledGroupByField> groupBy,
        List<CompiledAggregateFunction> aggregates,
        String having,
        CompiledOrigin origin,
        List<CompiledQueryLookup> lookups,
        List<CompiledQueryComputedColumn> computed
) {
    public CompiledQuery {
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        permissionRequirements = permissionRequirements == null ? List.of() : List.copyOf(permissionRequirements);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
        lookups = lookups == null ? List.of() : List.copyOf(lookups);
        computed = computed == null ? List.of() : List.copyOf(computed);
    }

    /** Pre-PACK-2 convenience constructor -- origin defaults to null (not pack-contributed). */
    public CompiledQuery(
            String name,
            String concept,
            String where,
            List<String> orderBy,
            Integer limit,
            List<CompiledProcedureParameter> parameters,
            List<String> permissionRequirements,
            Map<String, Object> metadata,
            List<CompiledGroupByField> groupBy,
            List<CompiledAggregateFunction> aggregates,
            String having
    ) {
        this(name, concept, where, orderBy, limit, parameters, permissionRequirements,
                metadata, groupBy, aggregates, having, null, List.of(), List.of());
    }

    /** Convenience constructor -- no lookups[] / computed[] columns. */
    public CompiledQuery(
            String name,
            String concept,
            String where,
            List<String> orderBy,
            Integer limit,
            List<CompiledProcedureParameter> parameters,
            List<String> permissionRequirements,
            Map<String, Object> metadata,
            List<CompiledGroupByField> groupBy,
            List<CompiledAggregateFunction> aggregates,
            String having,
            CompiledOrigin origin
    ) {
        this(name, concept, where, orderBy, limit, parameters, permissionRequirements,
                metadata, groupBy, aggregates, having, origin, List.of(), List.of());
    }

    /** Move 10 B1: a query with any groupBy/aggregates returns rows of aggregate output, not
     *  concept records -- a Panel/gadget data-source binding needs to know which shape it got. */
    public boolean isAggregate() {
        return !groupBy.isEmpty() || !aggregates.isEmpty();
    }
}
