package com.npdev.adapters.eventstore.postgres;

import com.npdev.adapters.eventstore.jdbc.JdbcEventStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * @deprecated Use {@link JdbcEventStore} for JDBC-backed NPDev internal storage.
 */
@Deprecated(forRemoval = false)
public class PostgresEventStore extends JdbcEventStore {
    public PostgresEventStore(DataSource dataSource) {
        super(dataSource);
    }

    public PostgresEventStore(DataSource dataSource, ObjectMapper objectMapper) {
        super(dataSource, objectMapper);
    }
}
