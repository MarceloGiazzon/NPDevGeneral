package com.npdev.kernel.storage.sql;

import java.util.List;
import java.util.Objects;

/**
 * A pagination suffix together with <b>the order its parameters must be bound in</b>.
 *
 * <p><b>Why this is a type and not a String.</b> Pagination is 23 of the 41 dialect-bound sites, and
 * every one of them binds placeholders rather than inlining numbers -- {@code LIMIT ? OFFSET ?}, not
 * {@code LIMIT 20 OFFSET 40}. Returning bare SQL text would silently assume every engine wants
 * (limit, offset) in that order, and that assumption is false:
 *
 * <pre>
 *   Postgres / H2   LIMIT ? OFFSET ?                              (limit, offset)
 *   MySQL           LIMIT ? OFFSET ?                              (limit, offset)   -- also LIMIT ?,?  (offset, limit)
 *   SQL Server      OFFSET ? ROWS FETCH NEXT ? ROWS ONLY          (OFFSET, LIMIT)   -- REVERSED
 * </pre>
 *
 * <p>A caller that hardcodes {@code setInt(n, limit); setInt(n + 1, offset)} works on two engines and
 * silently returns the wrong page on the third. That is a data defect, not a crash: the query
 * succeeds and the user sees records that are not there and misses records that are. Carrying the
 * order in the value makes the mistake unrepresentable.
 *
 * <p>Deliberately free of {@code java.sql}: the kernel has zero JDBC imports by design (ports and
 * adapters), and this type lives in the kernel so all three consumers can see it. Callers bind
 * {@link #values(int, int)} positionally in the order given.
 */
public record PaginationClause(String clause, List<Parameter> parameters) {

    /** Which value a placeholder in {@link #clause()} expects, in the order they appear. */
    public enum Parameter {
        LIMIT,
        OFFSET
    }

    public PaginationClause {
        Objects.requireNonNull(clause, "clause");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }

    /**
     * The values to bind, in the exact order the placeholders appear in {@link #clause()}.
     *
     * <p>Bind these positionally after whatever parameters the base statement already has:
     * <pre>
     *   int index = 3;
     *   for (int value : page.values(limit, offset)) {
     *       statement.setInt(index++, value);
     *   }
     * </pre>
     * or, where the site accumulates into a parameter list, {@code params.addAll(page.values(...))}.
     */
    public List<Integer> values(int limit, int offset) {
        return parameters.stream()
                .map(parameter -> parameter == Parameter.LIMIT ? limit : offset)
                .toList();
    }

    /** How many placeholders {@link #clause()} contributes. */
    public int parameterCount() {
        return parameters.size();
    }
}
