package com.npdev.adapters.flowinstance.postgres;

import com.npdev.adapters.flowinstance.jdbc.JdbcFlowInstanceStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * @deprecated Use {@link JdbcFlowInstanceStore} for JDBC-backed NPDev internal storage.
 */
@Deprecated(forRemoval = false)
public class PostgresFlowInstanceStore extends JdbcFlowInstanceStore {
    public PostgresFlowInstanceStore(DataSource dataSource) {
        super(dataSource);
    }

    public PostgresFlowInstanceStore(DataSource dataSource, ObjectMapper objectMapper) {
        super(dataSource, objectMapper);
    }
}
