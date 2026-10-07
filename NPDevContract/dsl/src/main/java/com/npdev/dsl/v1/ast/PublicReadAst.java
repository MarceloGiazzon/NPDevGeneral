package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * P6 (Pigmentampas public gallery, G4): an anonymous, read-only grant on a concept
 * ({@code access.public: { where, fields, scope }}), served only by the {@code /api/public/**} surface.
 *
 * <p>{@code where} narrows which rows are public (the {@code queries[].where} grammar, never
 * {@code $user} -- there is no caller); absent means every row. {@code fields} is an explicit
 * allow-list -- {@code id} is always included and nothing else leaks by default. {@code scope}
 * {@value #SCOPE_CONCEPT} (default) serves the concept's own list/get routes; {@value #SCOPE_AGGREGATE}
 * serves it only as a child collection inside a public aggregate root (e.g. a published mosaic's cells).
 */
public record PublicReadAst(String where, List<String> fields, String scope) {

    public static final String SCOPE_CONCEPT = "concept";
    public static final String SCOPE_AGGREGATE = "aggregate";

    public PublicReadAst {
        where = where == null || where.isBlank() ? null : where.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
        scope = scope == null || scope.isBlank() ? SCOPE_CONCEPT : scope.trim();
    }
}
