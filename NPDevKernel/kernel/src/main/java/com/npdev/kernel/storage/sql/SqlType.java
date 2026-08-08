package com.npdev.kernel.storage.sql;

/**
 * The column shapes the dialect layer has to spell differently per engine.
 *
 * <p>Deliberately NOT a general type system: the codebase already has
 * {@code com.npdev.kernel.dbschema.InternalColumnType} for internal tables and
 * {@code SqlTypeSupport} for modelled fields, and inventing a third would be the "build a query
 * DSL" trap. This enum exists only so {@link SqlDialect#autoIncrementColumn(SqlType)} and
 * {@link SqlDialect#cast(String, SqlType)} have something to name.
 */
public enum SqlType {
    TEXT,
    INT,
    BIGINT,
    UUID,
    BOOLEAN,
    NUMERIC,
    TIMESTAMP,
    JSON,
    BLOB
}
