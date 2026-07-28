package com.npdev.adapters.idempotency.inproc;

import com.npdev.kernel.capability.IdempotencyKeys;
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

    @Test
    void anOversizedKeyStillProducesAFindableRecord() {
        // REG-36. The in-proc map would happily hold a 100k-char key, which is precisely why the bound
        // is applied here too: without it, dev runs pass and only Postgres (btree index-entry limit)
        // rejects the write -- at which point an already-successful call is reported as failed and the
        // caller's retry re-executes it.
        InProcIdempotencyStore store = new InProcIdempotencyStore();
        String oversized = "k".repeat(100_000);

        store.saveSuccess("tenant-a", "persistence", "save", oversized, "{\"id\":\"u-1\"}", 1000L);

        IdempotencyRecord found = store.find("tenant-a", "persistence", "save", oversized).orElseThrow();
        assertEquals("{\"id\":\"u-1\"}", found.resultJsonRedacted());
        assertTrue(found.idempotencyKey().length() <= IdempotencyKeys.MAX_CHARS,
                "the stored key must be bounded, not merely the lookup");
    }

    @Test
    void twoDistinctOversizedKeysKeepDistinctOutcomes() {
        // Not "the strings differ" -- the outcomes must differ, which is the property that actually
        // matters: a collision would serve one caller another caller's cached result.
        InProcIdempotencyStore store = new InProcIdempotencyStore();
        String first = "k".repeat(50_000) + "-alpha";
        String second = "k".repeat(50_000) + "-beta";

        store.saveSuccess("tenant-a", "persistence", "save", first, "{\"id\":\"alpha\"}", 1000L);
        store.saveSuccess("tenant-a", "persistence", "save", second, "{\"id\":\"beta\"}", 1000L);

        assertEquals("{\"id\":\"alpha\"}",
                store.find("tenant-a", "persistence", "save", first).orElseThrow().resultJsonRedacted());
        assertEquals("{\"id\":\"beta\"}",
                store.find("tenant-a", "persistence", "save", second).orElseThrow().resultJsonRedacted());
    }

    @Test
    void anOversizedKeyIsStillScopedByTenant() {
        // Digesting must not flatten the rest of the composite key: tenant-b asking with the same
        // oversized key must not read tenant-a's cached result.
        InProcIdempotencyStore store = new InProcIdempotencyStore();
        String oversized = "k".repeat(100_000);

        store.saveSuccess("tenant-a", "persistence", "save", oversized, "{\"id\":\"a\"}", 1000L);

        assertTrue(store.find("tenant-b", "persistence", "save", oversized).isEmpty());
    }
}
