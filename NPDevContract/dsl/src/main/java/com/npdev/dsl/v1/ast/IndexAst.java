package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * LNCH-6: an author-declared secondary index on a concept ({@code indexes: []}), for query patterns
 * the implicit panel/query-predicate indexing (SchemaRealizationEmitter) doesn't cover -- a
 * multi-column index, or a field only ever touched by hand-authored SQL/procedures.
 */
public final class IndexAst {
    private final String name;
    private final List<String> fields;
    private final boolean unique;

    public IndexAst(String name, List<String> fields, boolean unique) {
        this.name = (name == null || name.isBlank()) ? null : name.trim();
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.unique = unique;
    }

    public String getName() {
        return name;
    }

    public List<String> getFields() {
        return fields;
    }

    public boolean isUnique() {
        return unique;
    }
}
