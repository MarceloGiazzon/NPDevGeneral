package com.finalexec.db.schemastate;

import java.util.List;

/**
 * A unique constraint the model intends (schema-engine rebuild, Phase 2). The desired side has no
 * stable constraint NAME (names are generated), so a desired unique is identified by its column set;
 * the diff compares column sets against {@link CurrentUniqueConstraint#columns()}.
 *
 * @param columns the constrained columns, lower-cased, in declaration order
 */
public record DesiredUniqueConstraint(List<String> columns) {
}
