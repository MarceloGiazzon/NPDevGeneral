package com.finalexec.db.schemastate;

/**
 * One column as the MODEL intends it (schema-engine rebuild, Phase 2) — the "desired" mirror of
 * {@link CurrentColumn}, built from the {@code SchemaManifest} + {@code ColumnFacts} by
 * {@code DesiredSchemaFactory}. Carries the per-column provenance the diff needs to apply the special
 * rules (§6 of DATABASES_AND_MIGRATIONS.md): platform columns are diffed differently from model fields.
 *
 * @param name               lower-cased column name
 * @param normalizedSqlType  declared type, run through the same {@code SqlTypeNormalization} the reader uses
 * @param nullable           whether the model allows NULL (meaningful for model fields; platform columns
 *                           are always NOT NULL and are governed by {@link #platformManaged} instead)
 * @param literalDefault     the literal-default JSON when the model declares one, else {@code null}
 * @param platformManaged    true for {@code id}/{@code version}/{@code row_version}/{@code tenant_id}
 * @param requiredByModel    true when the model declares the field required (⇒ NOT NULL for a model field)
 * @param bond               true when this is a bond/FK field (best-effort: a required non-additive column)
 * @param additiveEligible   whether this column may be safely ADDED to an existing table (the manifest's
 *                           {@code businessTableAdditiveColumns} membership, which is exactly what
 *                           {@code classify} tests — NOT a nullable/required proxy; a required bond is
 *                           marked non-additive, and a hand-built manifest may mark any column so)
 * @param renamedFromColumn  the prior column name when the model declares {@code renamedFrom}, else {@code null}
 */
public record DesiredColumn(
        String name,
        String normalizedSqlType,
        boolean nullable,
        String literalDefault,
        boolean platformManaged,
        boolean requiredByModel,
        boolean bond,
        boolean additiveEligible,
        String renamedFromColumn
) {
}
