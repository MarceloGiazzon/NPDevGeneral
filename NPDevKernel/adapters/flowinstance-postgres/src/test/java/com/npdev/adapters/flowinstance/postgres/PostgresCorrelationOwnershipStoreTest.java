package com.npdev.adapters.flowinstance.postgres;

import com.npdev.kernel.CorrelationOwnershipViolationException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresCorrelationOwnershipStoreTest {
    private PostgresCorrelationOwnershipStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:corr_owner_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("sa");
        executeSchema(dataSource);
        store = new PostgresCorrelationOwnershipStore(dataSource);
    }

    @Test
    void claimIsIdempotentForSameTenantAndRejectsOtherTenant() {
        store.claimCorrelation("corr-1", "tenant-a");
        store.claimCorrelation("corr-1", "tenant-a");
        assertEquals("tenant-a", store.findTenantByCorrelationId("corr-1").orElseThrow());

        CorrelationOwnershipViolationException exception = assertThrows(
                CorrelationOwnershipViolationException.class,
                () -> store.claimCorrelation("corr-1", "tenant-b")
        );
        assertEquals("corr-1", exception.correlationId());
        assertEquals("tenant-a", exception.ownerTenantId());
        assertEquals("tenant-b", exception.requesterTenantId());
    }

    private static void executeSchema(DataSource dataSource) {
        String sql = """
                CREATE TABLE IF NOT EXISTS npdev_correlation_owner (
                    correlation_id TEXT PRIMARY KEY,
                    tenant_id TEXT NOT NULL
                )
                """;
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed preparing correlation ownership schema", exception);
        }
    }
}
