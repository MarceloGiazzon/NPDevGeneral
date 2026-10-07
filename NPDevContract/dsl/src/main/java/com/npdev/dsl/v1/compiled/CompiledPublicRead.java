package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * P6: compiled form of {@code access.public} ({@link com.npdev.dsl.v1.ast.PublicReadAst}) -- the
 * anonymous read grant the RuntimeHost {@code /api/public/**} surface serves. {@code fields} is the
 * allow-list (without {@code id}, which is always served); {@code scope} is {@code concept} or
 * {@code aggregate}.
 */
public record CompiledPublicRead(String where, List<String> fields, String scope) {

    public CompiledPublicRead {
        where = where == null || where.isBlank() ? null : where.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
        scope = scope == null || scope.isBlank() ? "concept" : scope.trim();
    }

    /** True when the concept has its own public list/get routes (not only as an aggregate child). */
    public boolean directlyReadable() {
        return "concept".equals(scope);
    }
}
