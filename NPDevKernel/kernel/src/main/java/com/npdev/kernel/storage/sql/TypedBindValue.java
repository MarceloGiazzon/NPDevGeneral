package com.npdev.kernel.storage.sql;

/**
 * A {@link SqlDialect#bindableValue} result that must be bound via the 3-arg
 * {@code PreparedStatement.setObject(index, value, sqlType)} rather than the plain 2-arg form every
 * other shaped value uses. {@code sqlType} is a {@link java.sql.Types} constant, not a driver-specific
 * class -- callers stay driver-agnostic (no {@code org.postgresql.util.PGobject} import needed
 * anywhere outside the JDBC driver itself).
 *
 * <p>Exists for exactly one case today: Postgres json/jsonb columns. A plain 2-arg
 * {@code setObject(index, jsonBytes)} sends a {@code byte[]} as {@code bytea} (STOR-10-class bug,
 * measured live: "column is of type json but expression is of type bytea"), and a plain
 * {@code String} bind sends {@code varchar}, for which Postgres has no implicit assignment cast to
 * json/jsonb. {@link java.sql.Types#OTHER} tells the driver to send the parameter untyped, so the
 * server infers the real type from the target column instead of guessing.
 */
public record TypedBindValue(Object value, int sqlType) {
}
