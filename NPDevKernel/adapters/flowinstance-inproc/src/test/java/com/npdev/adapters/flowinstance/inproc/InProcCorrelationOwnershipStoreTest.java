package com.npdev.adapters.flowinstance.inproc;

import com.npdev.kernel.CorrelationOwnershipViolationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InProcCorrelationOwnershipStoreTest {

    @Test
    void claimIsIdempotentForSameTenantAndRejectsOtherTenant() {
        InProcCorrelationOwnershipStore store = new InProcCorrelationOwnershipStore();

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
