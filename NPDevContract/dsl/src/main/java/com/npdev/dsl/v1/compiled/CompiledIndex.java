package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * LNCH-6: a compiled author-declared secondary index on a concept (from {@code indexes:[]}), distinct
 * from the implicit tenant-composite indexes {@code SchemaRealizationEmitter} derives from compiled
 * panel/query predicates. Supports multi-column indexes and an optional {@code unique} flag for
 * indexing intent the implicit mechanism cannot express.
 */
public final class CompiledIndex {
    private final String name;
    private final List<String> fields;
    private final boolean unique;

    public CompiledIndex(String name, List<String> fields, boolean unique) {
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
