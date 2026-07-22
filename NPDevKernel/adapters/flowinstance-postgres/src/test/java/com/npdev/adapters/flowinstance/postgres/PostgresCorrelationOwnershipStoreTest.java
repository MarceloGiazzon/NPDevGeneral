package com.npdev.adapters.flowinstance.postgres;

import com.npdev.kernel.CorrelationOwnershipViolationException;
import com.npdev.test.postgres.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresCorrelationOwnershipStoreTest {
    private PostgresCorrelationOwnershipStore store;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresTestSupport.dataSource();
        PostgresTestSupport.execute(dataSource, """
                CREATE TABLE IF NOT EXISTS npdev_correlation_owner (
                    correlation_id TEXT PRIMARY KEY,
                    tenant_id TEXT NOT NULL
                )
                """);
        PostgresTestSupport.truncate(dataSource, "npdev_correlation_owner");
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

}
