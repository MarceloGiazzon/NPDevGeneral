package com.npdev.adapters.audit.postgres;

import com.npdev.adapters.audit.jdbc.JdbcAuditLogStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

/**
 * @deprecated Use {@link JdbcAuditLogStore} for JDBC-backed NPDev internal storage.
 */
@Deprecated(forRemoval = false)
public class PostgresAuditLogStore extends JdbcAuditLogStore {
    public PostgresAuditLogStore(DataSource dataSource) {
        super(dataSource);
    }

    public PostgresAuditLogStore(DataSource dataSource, ObjectMapper objectMapper) {
        super(dataSource, objectMapper);
    }
}
