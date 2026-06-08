package com.npdev.adapters.idempotency.postgres;

import com.npdev.adapters.idempotency.jdbc.JdbcIdempotencyStore;

import javax.sql.DataSource;

/**
 * @deprecated Use {@link JdbcIdempotencyStore} for JDBC-backed NPDev internal storage.
 */
@Deprecated(forRemoval = false)
public class PostgresIdempotencyStore extends JdbcIdempotencyStore {
    public PostgresIdempotencyStore(DataSource dataSource) {
        super(dataSource);
    }
}
