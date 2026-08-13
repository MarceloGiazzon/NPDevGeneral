package com.finalexec.db.schemastate;

import java.util.List;

/**
 * S8 Wave 2: one live FK or index classified
 * {@link ConstraintSurplusClassifier.Classification#FOREIGN} — the model does not declare it and the
 * database did not need to create it. Advisory only; see {@link ConstraintSurplusReport}'s own javadoc
 * for why this never feeds a drop path.
 *
 * @param table           lower-cased table name
 * @param kind            {@code "INDEX"} or {@code "FOREIGN_KEY"}
 * @param liveName        the live constraint's own name (index/FK names ARE stable identifiers for
 *                        DISPLAY purposes, even though classification never matches on them)
 * @param columns         the live constraint's columns, lower-cased, in their live order
 * @param unique          whether a surplus INDEX enforces uniqueness (always {@code false} for a FK)
 * @param referencedTable the surplus FK's referenced table, lower-cased ({@code null} for an index)
 */
public record SurplusConstraint(
        String table,
        String kind,
        String liveName,
        List<String> columns,
        boolean unique,
        String referencedTable
) {
}
