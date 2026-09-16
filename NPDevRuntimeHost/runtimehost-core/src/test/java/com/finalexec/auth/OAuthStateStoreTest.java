package com.finalexec.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-11: the OAuth state store's three binding properties -- unique per mint, single-use on
 * consume, expired entries never accepted. No OAuth logic here, just the CSRF primitive.
 */
class OAuthStateStoreTest {

    @Test
    void statesAreUniqueAndCarryPurposeAndNext() {
        OAuthStateStore store = new OAuthStateStore(60_000L);

        String a = store.create("login", "/");
        String b = store.create("link", "/profile");

        assertFalse(a.equals(b), "two mints must never collide");
        OAuthStateStore.Pending pendingA = store.consume(a).orElseThrow();
        assertEquals("login", pendingA.purpose());
        assertEquals("/", pendingA.next());
    }

    @Test
    void consumeIsSingleUse() {
        OAuthStateStore store = new OAuthStateStore(60_000L);
        String state = store.create("login", "/");

        assertTrue(store.consume(state).isPresent());
        assertTrue(store.consume(state).isEmpty(), "the same state must not consume twice");
    }

    @Test
    void unknownStateIsIndistinguishableFromConsumed() {
        OAuthStateStore store = new OAuthStateStore(60_000L);

        assertTrue(store.consume("deadbeef").isEmpty());
        assertTrue(store.consume(null).isEmpty());
        assertTrue(store.consume("").isEmpty());
    }

    @Test
    void expiredStateIsNeverAccepted() throws Exception {
        OAuthStateStore store = new OAuthStateStore(50L);
        String state = store.create("login", "/");

        Thread.sleep(120L);
        assertTrue(store.consume(state).isEmpty(), "an expired state must be refused");
    }
}