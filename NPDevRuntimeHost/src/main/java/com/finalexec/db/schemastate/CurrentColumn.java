package com.finalexec.db.schemastate;

/**
 * One column as it actually exists in the live database, read once per boot by {@code CurrentSchemaReader}
 * (schema-engine rebuild, Phase 1). Part of the {@code CurrentSchema} model that replaces the ~12 ad-hoc
 * {@code DatabaseMetaData} reads scattered across {@code SchemaLifecycleExecutor}'s passes (REG-6).
 *
 * @param name                   lower-cased column name (JDBC catalogs are case-inconsistent across H2/Postgres)
 * @param normalizedSqlType      SQL type run through {@code SqlTypeNormalization.normalize} so H2 and Postgres
 *                               spellings compare equal (e.g. {@code character varying} == {@code VARCHAR})
 * @param size                   column size / precision where meaningful (e.g. varchar length), else {@code null}
 * @param scale                  numeric scale where meaningful, else {@code null}
 * @param nullable               whether the live column permits NULL
 * @param defaultValueNormalized the live default expression, engine-normalized, or {@code null} when none
 */
public record CurrentColumn(
        String name,
        String normalizedSqlType,
        Integer size,
        Integer scale,
        boolean nullable,
        String defaultValueNormalized
) {
}
