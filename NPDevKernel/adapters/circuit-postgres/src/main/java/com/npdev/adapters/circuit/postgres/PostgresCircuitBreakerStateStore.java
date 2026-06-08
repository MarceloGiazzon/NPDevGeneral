package com.npdev.adapters.circuit.postgres;

import com.npdev.adapters.circuit.jdbc.JdbcCircuitBreakerStateStore;

import javax.sql.DataSource;

/**
 * @deprecated Use {@link JdbcCircuitBreakerStateStore} for JDBC-backed NPDev internal storage.
 */
@Deprecated(forRemoval = false)
public class PostgresCircuitBreakerStateStore extends JdbcCircuitBreakerStateStore {
    public PostgresCircuitBreakerStateStore(DataSource dataSource) {
        super(dataSource);
    }
}
