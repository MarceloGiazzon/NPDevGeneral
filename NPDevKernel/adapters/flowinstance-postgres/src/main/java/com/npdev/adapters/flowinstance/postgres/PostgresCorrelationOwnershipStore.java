package com.npdev.adapters.flowinstance.postgres;

import com.npdev.adapters.flowinstance.jdbc.JdbcCorrelationOwnershipStore;

import javax.sql.DataSource;

/**
 * @deprecated Use {@link JdbcCorrelationOwnershipStore} for JDBC-backed NPDev internal storage.
 */
@Deprecated(forRemoval = false)
public class PostgresCorrelationOwnershipStore extends JdbcCorrelationOwnershipStore {
    public PostgresCorrelationOwnershipStore(DataSource dataSource) {
        super(dataSource);
    }
}
