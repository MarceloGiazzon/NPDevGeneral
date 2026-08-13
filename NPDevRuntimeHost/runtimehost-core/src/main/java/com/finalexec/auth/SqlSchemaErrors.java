package com.finalexec.auth;

import java.sql.SQLException;

/**
 * REG-39: tells apart a genuine schema mismatch (a column/table platform code expects is missing --
 * e.g. an app's stale built-in-pack copy predating a platform column addition) from every other
 * {@link SQLException}, so the two are never conflated into the same generic outcome. SQLState class
 * {@code "42"} (syntax error / access rule violation) covers "column not found" / "table not found" on
 * both engines this platform targets: H2 ({@code 42122} unknown column, {@code 42102} unknown table)
 * and Postgres ({@code 42703} undefined_column, {@code 42P01} undefined_table).
 */
public final class SqlSchemaErrors {

    private SqlSchemaErrors() {
    }

    public static boolean isSchemaMismatch(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && state.startsWith("42");
    }
}
