package com.npdev.kernel.storage.sql;

import java.util.List;

/**
 * How a generated key comes back from an insert.
 *
 * <p><b>Measured on 5680551: ZERO production sites use {@code RETURNING}.</b> That matters, because
 * inline return of generated keys is MySQL's one genuinely structural gap -- it needs a second query
 * plus {@code LAST_INSERT_ID()}, which changes the NUMBER of statements rather than their spelling,
 * and a number of statements is the one thing a text-returning interface cannot hide.
 *
 * <p>So the shape exists here and nothing implements the two-statement path. That is deliberate:
 * building an escape hatch on speculation means maintaining, and eventually trusting, a code path no
 * caller exercises. <b>Conformance vector A2 ("the generated key is readable after insert") is what
 * will tell you the day a site needs it</b> -- add it when A2 fails, not before.
 */
public interface ReturningStrategy {

    /** True when the engine can return generated columns from the insert itself. */
    boolean isInline();

    /**
     * The clause appended to the insert, e.g. {@code RETURNING id}.
     *
     * @throws UnsupportedStorageCapabilityException when {@link #isInline()} is false -- never a
     *         silent empty string, which would turn "cannot do this" into "returned no rows"
     */
    String inlineClause(List<String> columns);

    /**
     * The follow-up statement that reads the key the previous insert generated, e.g.
     * {@code SELECT LAST_INSERT_ID()}.
     *
     * @throws UnsupportedStorageCapabilityException when {@link #isInline()} is true -- asking an
     *         inline engine for a second query is a caller bug worth surfacing
     */
    String secondQuerySql();
}
