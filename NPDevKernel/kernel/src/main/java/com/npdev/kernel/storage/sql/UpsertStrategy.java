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
}
