package com.npdev.adapters.tracestore.postgres;

import com.npdev.adapters.tracestore.jdbc.JdbcTraceStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * @deprecated Use {@link JdbcTraceStore} for JDBC-backed NPDev internal storage.
 */
@Deprecated(forRemoval = false)
public class PostgresTraceStore extends JdbcTraceStore {
    public PostgresTraceStore(DataSource dataSource) {
        super(dataSource);
    }

    public PostgresTraceStore(DataSource dataSource, ObjectMapper objectMapper) {
        super(dataSource, objectMapper);
    }
}
