package com.finalexec.db.schemastate;

import java.util.List;

/**
 * An index the MODEL declares (schema-engine rebuild, SER-G8) — a unique index per unique invariant, a
 * lookup index per bond column. Name-less for the same reason as {@link DesiredForeignKey}.
 *
 * <p>The model never declares the implicit index backing a primary key; the diff treats a live PK over
 * the same columns as satisfying a declared index.
 */
public record DesiredIndex(List<String> columns, boolean unique) {
}
