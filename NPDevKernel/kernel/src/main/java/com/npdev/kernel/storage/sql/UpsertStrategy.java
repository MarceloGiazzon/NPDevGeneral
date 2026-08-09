package com.npdev.kernel.storage.sql;

import java.util.List;

/**
 * Emits a complete "insert, or update if the key is already there" statement.
 *
 * <p><b>A strategy, not a string template</b>, because the three engines do not differ by keyword --
 * they differ by statement SHAPE:
 *
 * <pre>
 *   Postgres     INSERT ... VALUES (...) ON CONFLICT (k) DO UPDATE SET ...
 *   MySQL        INSERT ... VALUES (...) ON DUPLICATE KEY UPDATE ...
 *   H2           MERGE INTO t (...) KEY (k) VALUES (...)
 *   SQL Server   MERGE t USING (...) AS s ON ... WHEN MATCHED THEN UPDATE ... WHEN NOT MATCHED THEN INSERT ...
 * </pre>
 *
 * <p>SQL Server's {@code MERGE} is a different statement with a different parameter count, so a
 * caller cannot assemble it from a suffix. It also has documented concurrency hazards under default
 * isolation and generally needs an explicit lock hint -- conformance vector U2 exists to catch a
 * naive translation that only fails under load.
 */
public interface UpsertStrategy {

    /**
     * A full upsert statement for {@code table}.
     *
     * @param table        the (already-quoted-if-needed) table name
     * @param keyColumns   the columns that decide "already there"
     * @param valueColumns every column being written, key columns included, in binding order
     * @return one complete statement whose placeholders bind {@code valueColumns} in the given order
     */
    String statementFor(String table, List<String> keyColumns, List<String> valueColumns);

    /**
     * The same operation as an executable PLAN -- <b>this is what production callers use.</b>
     *
     * <p>{@link #statementFor} answers "what is this engine's native upsert", which is the right
     * question for a conformance vector and the wrong one for a write path, because it assumes the
     * statement reacts only to the key the caller named. On MySQL it does not: {@code ON DUPLICATE
     * KEY UPDATE} fires for a clash with ANY unique index, so a create whose {@code unique: true}
     * field collides silently OVERWRITES the row that held the value and returns success (STOR-11,
     * measured on a real MySQL 8.4).
     *
     * <p>The default keeps every engine that can name its conflict target on its single atomic
     * statement. MySQL overrides it with UPDATE-then-INSERT, after which the clash arrives as a
     * genuine unique violation -- the same one Postgres raises, mapped to the same 409 by the same
     * code above. Parity is what the user sees, so matching the STATUS and BODY is the requirement,
     * not merely "an error on both".
     *
     * @see UpsertPlan for the execution rule and why bind order travels with each statement
     */
    default UpsertPlan planFor(String table, List<String> keyColumns, List<String> valueColumns) {
        return UpsertPlan.single(statementFor(table, keyColumns, valueColumns), valueColumns);
    }
}
