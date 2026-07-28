package com.npdev.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REG-47 — the correlation-id length bound, and the properties the choice of "reject" turns on. */
class CorrelationIdsTest {

    @Test
    void realCorrelationIdsPassThroughUnchanged() {
        // The bound must not be felt by anything anyone actually sends. A UUID is 36 chars and a W3C
        // traceparent 55, both far under the ceiling.
        assertEquals("6f1b6f0e-9a1e-4a3e-9d8f-2f6a1c0b7e11",
                CorrelationIds.require("6f1b6f0e-9a1e-4a3e-9d8f-2f6a1c0b7e11"));
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                CorrelationIds.require("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        assertEquals("order-42", CorrelationIds.require("  order-42  "), "still trimmed");
    }

    @Test
    void absentCorrelationIdsStayAbsent() {
        // Every caller already treats null/blank as "no correlation id"; the bound must not turn that
        // into an error.
        assertNull(CorrelationIds.require(null));
        assertNull(CorrelationIds.require("   "));
    }

    @Test
    void anOversizedCorrelationIdIsRejectedRatherThanShortened() {
        // The decision this pins: REJECT, not digest. Digesting would store an id different from the
        // one the caller holds, and the caller looks it up again via @PathVariable on the
        // correlation-timeline and event-query controllers -- so a digest would have to be applied
        // identically at every lookup site or those endpoints would quietly return nothing.
        String oversized = "x".repeat(CorrelationIds.MAX_CHARS + 1);

        IllegalArgumentException rejected =
                assertThrows(IllegalArgumentException.class, () -> CorrelationIds.require(oversized));

        assertTrue(rejected.getMessage().contains(String.valueOf(CorrelationIds.MAX_CHARS)), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("REG-47"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("index key material"), rejected.getMessage());
    }

    @Test
    void theBoundaryItselfIsAllowed() {
        // Off-by-one matters here: MAX_CHARS is a documented contract, so exactly MAX_CHARS must pass.
        String atLimit = "y".repeat(CorrelationIds.MAX_CHARS);
        assertEquals(atLimit, CorrelationIds.require(atLimit));
        assertThrows(IllegalArgumentException.class, () -> CorrelationIds.require("y".repeat(CorrelationIds.MAX_CHARS + 1)));
    }

    @Test
    void trimmingHappensBeforeTheLengthIsJudged() {
        // Otherwise padding a legal id with whitespace would fail it -- the trim is part of the
        // contract, not a separate step callers must perform first.
        String padded = "   " + "z".repeat(CorrelationIds.MAX_CHARS) + "   ";
        assertEquals("z".repeat(CorrelationIds.MAX_CHARS), CorrelationIds.require(padded));
    }

    @Test
    void theCeilingLeavesRoomForTheCompositeIndexesItProtects() {
        // correlation_id shares composite btree indexes with tenant_id and timestamp_ms, so the bound
        // has to hold with those alongside it, not just on its own. Postgres's limit is ~2704 bytes;
        // 400 chars is under it even at 4 bytes per character with room to spare.
        assertTrue(CorrelationIds.MAX_CHARS * 4 < 2000,
                "MAX_CHARS must stay comfortably inside the btree index-entry limit alongside its "
                        + "composite-index companions");
    }
}
