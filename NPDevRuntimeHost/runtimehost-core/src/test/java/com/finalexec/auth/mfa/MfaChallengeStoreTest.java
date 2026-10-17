package com.finalexec.auth.mfa;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MfaChallengeStoreTest {

    /** A clock the test can move. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-17T10:00:00Z");

        void advanceMinutes(long minutes) {
            now = now.plusSeconds(minutes * 60);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void createPeekConsume() {
        MfaChallengeStore store = new MfaChallengeStore(new MovableClock());
        String id = store.create("dev", "ada", "/orders");
        assertEquals(64, id.length());
        MfaChallengeStore.Challenge c = store.peek(id).orElseThrow();
        assertEquals("dev", c.tenantId());
        assertEquals("ada", c.username());
        assertEquals("/orders", c.next());
        // peek does not consume: a wrong code must not force the password again
        assertTrue(store.peek(id).isPresent());
        store.consume(id);
        assertTrue(store.peek(id).isEmpty());
    }

    @Test
    void nextDefaultsToRootAndIdsAreUnique() {
        MfaChallengeStore store = new MfaChallengeStore(new MovableClock());
        String a = store.create("dev", "ada", null);
        String b = store.create("dev", "ada", null);
        assertNotEquals(a, b);
        assertEquals("/", store.peek(a).orElseThrow().next());
    }

    @Test
    void expiresAfterFiveMinutes() {
        MovableClock clock = new MovableClock();
        MfaChallengeStore store = new MfaChallengeStore(clock);
        String id = store.create("dev", "ada", null);
        clock.advanceMinutes(4);
        assertTrue(store.peek(id).isPresent());
        clock.advanceMinutes(2);
        assertTrue(store.peek(id).isEmpty());
    }

    @Test
    void diesAfterFiveWrongCodes() {
        MfaChallengeStore store = new MfaChallengeStore(new MovableClock());
        String id = store.create("dev", "ada", null);
        for (int i = 0; i < MfaChallengeStore.MAX_ATTEMPTS - 1; i++) {
            store.recordFailure(id);
            assertTrue(store.peek(id).isPresent(), "still alive after " + (i + 1) + " failures");
        }
        store.recordFailure(id);
        assertTrue(store.peek(id).isEmpty());
    }

    @Test
    void unknownBlankAndNullAreEmpty() {
        MfaChallengeStore store = new MfaChallengeStore(new MovableClock());
        assertTrue(store.peek("nope").isEmpty());
        assertTrue(store.peek("").isEmpty());
        assertTrue(store.peek(null).isEmpty());
        store.recordFailure(null);
        store.consume(null);
    }
}
