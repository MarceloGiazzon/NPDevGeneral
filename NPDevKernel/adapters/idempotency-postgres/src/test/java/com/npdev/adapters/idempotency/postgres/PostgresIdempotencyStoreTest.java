package com.npdev.adapters.idempotency.postgres;

import com.npdev.kernel.capability.IdempotencyRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresIdempotencyStoreTest {
    private static final String[] SCHEMA_SQL = new String[]{
            """
            CREATE TABLE IF NOT EXISTS npdev_idempotency (
                tenant_id TEXT NOT NULL,
                capability TEXT NOT NULL,
                operation TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,
                created_at_ms BIGINT NOT NULL,
                status TEXT NOT NULL,
                result_json_redacted TEXT,
                error_code TEXT,
                PRIMARY KEY (tenant_id, capability, operation, idempotency_key)
            )
            """
    };

    private PostgresIdempotencyStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:idempotencystore;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("sa");
        executeSchema(dataSource, SCHEMA_SQL);
        store = new PostgresIdempotencyStore(dataSource);
    }

    @Test
    void savesAndFindsRecordsAndUpsertsByCompositeKey() {
        store.saveSuccess("tenant-a", "persistence", "save", "idem-1", "{\"id\":\"u-1\"}", 1000L);

        IdempotencyRecord success = store.find("tenant-a", "persistence", "save", "idem-1").orElseThrow();
        assertTrue(success.success());
        assertEquals("{\"id\":\"u-1\"}", success.resultJsonRedacted());

        store.saveFailure("tenant-a", "persistence", "save", "idem-1", "PERMANENT:DB_DOWN", 2000L);
        IdempotencyRecord failure = store.find("tenant-a", "persistence", "save", "idem-1").orElseThrow();
        assertEquals(IdempotencyRecord.STATUS_FAILED, failure.status());
        assertEquals("PERMANENT:DB_DOWN", failure.errorCode());
        assertEquals(2000L, failure.createdAtMs());
    }

    private static void executeSchema(DataSource dataSource, String[] statements) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            for (String raw : statements) {
                String sql = raw.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                statement.execute(sql);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed preparing idempotency schema", exception);
        }
    }
}
