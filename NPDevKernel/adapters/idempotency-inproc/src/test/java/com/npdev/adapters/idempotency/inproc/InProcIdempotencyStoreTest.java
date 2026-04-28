package com.npdev.adapters.idempotency.inproc;

import com.npdev.kernel.capability.IdempotencyRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcIdempotencyStoreTest {
    @Test
    void storesAndFindsSuccessAndFailureByCompositeKey() {
        InProcIdempotencyStore store = new InProcIdempotencyStore();

        store.saveSuccess("tenant-a", "persistence", "save", "idem-1", "{\"id\":\"u-1\"}", 1000L);
        IdempotencyRecord success = store.find("tenant-a", "persistence", "save", "idem-1").orElseThrow();
        assertTrue(success.success());
        assertEquals("{\"id\":\"u-1\"}", success.resultJsonRedacted());

        store.saveFailure("tenant-a", "persistence", "save", "idem-2", "PERMANENT:DB_DOWN", 2000L);
        IdempotencyRecord failure = store.find("tenant-a", "persistence", "save", "idem-2").orElseThrow();
        assertEquals(IdempotencyRecord.STATUS_FAILED, failure.status());
        assertEquals("PERMANENT:DB_DOWN", failure.errorCode());
    }
}
