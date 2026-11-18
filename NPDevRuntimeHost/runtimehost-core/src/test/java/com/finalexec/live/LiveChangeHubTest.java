package com.finalexec.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** FORM P5-T5: the hub's own rules -- tenant isolation, concept filter, bounded queue, per-user cap, heartbeat, closing. */
class LiveChangeHubTest {

    /** Records what a subscriber was sent. */
    private static final class Recorder implements LiveChangeHub.Sink {
        final List<LiveChange> changes = new ArrayList<>();
        int beats;
        boolean failNext;

        @Override
        public synchronized void changed(LiveChange change) throws Exception {
            if (failNext) {
                throw new java.io.IOException("peer gone");
            }
            changes.add(change);
        }

        @Override
        public synchronized void heartbeat() {
            beats++;
        }
    }

    /** Direct delivery: a publish is observable as soon as it returns. */
    private static LiveChangeHub direct(int perUser, int queue) {
        return new LiveChangeHub(perUser, queue, 0, Runnable::run);
    }

    @Test
    void tenantAnEventNeverReachesTenantB() {
        LiveChangeHub hub = direct(5, 16);
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        hub.subscribe("tenant-a", "u1", List.of("Ride"), a);
        hub.subscribe("tenant-b", "u1", List.of("Ride"), b);
        hub.publish("tenant-a", "Ride", "r1", 3L);
        assertEquals(List.of(new LiveChange("Ride", "r1", 3L)), a.changes);
        assertTrue(b.changes.isEmpty(), "tenant B must never see tenant A's notice");
        hub.publish("TENANT-B ", "Ride", "r2", 1L); // tenant keys are trimmed and case-insensitive, like ExecutionContext
        assertEquals(1, b.changes.size());
        assertEquals(1, a.changes.size());
    }

    @Test
    void onlyTheRequestedConceptsAreDelivered_caseInsensitive() {
        LiveChangeHub hub = direct(5, 16);
        Recorder r = new Recorder();
        hub.subscribe("t", "u", List.of("ride", "RideOffer"), r);
        hub.publish("t", "Ride", "1", 1L);
        hub.publish("t", "Driver", "2", 1L);
        hub.publish("t", "rideoffer", "3", null);
        assertEquals(List.of("Ride", "rideoffer"), r.changes.stream().map(LiveChange::concept).toList());
        assertEquals(null, r.changes.get(1).version());
    }

    @Test
    void publishWithNobodyListeningIsCheapAndSilent() {
        LiveChangeHub hub = direct(5, 16);
        hub.publish("nobody", "Ride", "1", 1L);
        hub.publish(null, null, null, null);
        assertEquals(0, hub.publishedCount());
        assertEquals(0, hub.subscriberCount());
    }

    @Test
    void perUserStreamCapAndRelease() {
        LiveChangeHub hub = direct(2, 16);
        LiveChangeHub.Subscription first = hub.subscribe("t", "u", List.of("Ride"), new Recorder());
        hub.subscribe("t", "u", List.of("Ride"), new Recorder());
        assertThrows(LiveChangeHub.TooManyStreamsException.class, () -> hub.subscribe("t", "u", List.of("Ride"), new Recorder()));
        hub.subscribe("t", "other", List.of("Ride"), new Recorder()); // another user is unaffected
        hub.subscribe("t2", "u", List.of("Ride"), new Recorder()); // the same user name in another tenant is another user
        first.close();
        first.close(); // idempotent: must not free a second slot
        hub.subscribe("t", "u", List.of("Ride"), new Recorder());
        assertThrows(LiveChangeHub.TooManyStreamsException.class, () -> hub.subscribe("t", "u", List.of("Ride"), new Recorder()));
    }

    @Test
    void closedSubscriptionReceivesNothingAndIsRemoved() {
        LiveChangeHub hub = direct(5, 16);
        Recorder r = new Recorder();
        LiveChangeHub.Subscription sub = hub.subscribe("t", "u", List.of("Ride"), r);
        sub.close();
        assertTrue(sub.isClosed());
        assertEquals(0, hub.subscriberCount());
        hub.publish("t", "Ride", "1", 1L);
        assertTrue(r.changes.isEmpty());
    }

    @Test
    void aFailingSinkEndsItsSubscriptionButNotTheOthers() {
        LiveChangeHub hub = direct(5, 16);
        Recorder broken = new Recorder();
        Recorder healthy = new Recorder();
        hub.subscribe("t", "u1", List.of("Ride"), broken);
        hub.subscribe("t", "u2", List.of("Ride"), healthy);
        broken.failNext = true;
        hub.publish("t", "Ride", "1", 1L); // must not throw
        assertEquals(1, hub.subscriberCount(), "the dead peer is dropped");
        assertEquals(1, healthy.changes.size());
        hub.publish("t", "Ride", "2", 1L);
        assertEquals(2, healthy.changes.size());
    }

    @Test
    void queueIsBounded_dropsTheOldest_andCoalescesTheSameRow() throws Exception {
        // a delivery executor that holds the work back so the queue fills up
        List<Runnable> held = new ArrayList<>();
        LiveChangeHub hub = new LiveChangeHub(5, 3, 0, held::add);
        Recorder r = new Recorder();
        LiveChangeHub.Subscription sub = hub.subscribe("t", "u", List.of("Ride"), r);
        for (int i = 1; i <= 5; i++) {
            hub.publish("t", "Ride", "row" + i, (long) i);
        }
        hub.publish("t", "Ride", "row5", 6L); // same row again: replaces, does not take another slot
        assertEquals(1, held.size(), "one drain is scheduled however many notices are queued");
        held.get(0).run();
        assertEquals(List.of("row3", "row4", "row5"), r.changes.stream().map(LiveChange::id).toList());
        assertEquals(6L, r.changes.get(2).version(), "the newest version of a coalesced row wins");
        assertEquals(2, sub.droppedCount());
    }

    @Test
    void aBurstOnOneRowArrivesOnce() {
        List<Runnable> held = new ArrayList<>();
        LiveChangeHub hub = new LiveChangeHub(5, 8, 0, held::add);
        Recorder r = new Recorder();
        hub.subscribe("t", "u", List.of("Ride"), r);
        for (int v = 1; v <= 20; v++) {
            hub.publish("t", "Ride", "same", (long) v);
        }
        held.forEach(Runnable::run);
        assertEquals(1, r.changes.size());
        assertEquals(20L, r.changes.get(0).version());
    }

    @Test
    void heartbeatReachesEverySubscriber() throws Exception {
        LiveChangeHub hub = new LiveChangeHub(5, 8, 30, null);
        try {
            Recorder r = new Recorder();
            hub.subscribe("t", "u", List.of("Ride"), r);
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (r) {
                    if (r.beats >= 2) {
                        break;
                    }
                }
                Thread.sleep(10);
            }
            synchronized (r) {
                assertTrue(r.beats >= 2, "heartbeats are periodic, got " + r.beats);
            }
        } finally {
            hub.shutdown();
        }
    }

    @Test
    void aSlowSinkNeverBlocksThePublisher() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        LiveChangeHub hub = new LiveChangeHub(5, 8, 0, null); // real delivery pool
        try {
            LiveChangeHub.Sink stuck = new LiveChangeHub.Sink() {
                @Override
                public void changed(LiveChange change) throws Exception {
                    release.await(10, TimeUnit.SECONDS);
                }

                @Override
                public void heartbeat() {
                }
            };
            hub.subscribe("t", "u", List.of("Ride"), stuck);
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                hub.publish("t", "Ride", "r" + (i % 50), (long) i);
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 1000, "1000 publishes against a stuck reader took " + millis + " ms");
        } finally {
            release.countDown();
            hub.shutdown();
        }
    }

    @Test
    void shutdownClosesEverything() {
        LiveChangeHub hub = direct(5, 8);
        LiveChangeHub.Subscription sub = hub.subscribe("t", "u", List.of("Ride"), new Recorder());
        hub.shutdown();
        assertTrue(sub.isClosed());
        assertFalse(hub.subscriberCount() > 0);
    }

    @Test
    void jsonLineIsExactlyTheDocumentedShape_andEscapesHostileIds() {
        assertEquals("{\"concept\":\"Ride\",\"id\":\"r-1\",\"version\":3}", new LiveChange("Ride", "r-1", 3L).toJson());
        assertEquals("{\"concept\":\"Ride\",\"id\":\"r-1\",\"version\":null}", new LiveChange("Ride", "r-1", null).toJson());
        String hostile = new LiveChange("Ride", "a\"b\\c\nd <script>", 1L).toJson();
        assertEquals("{\"concept\":\"Ride\",\"id\":\"a\\\"b\\\\c\\nd\\u2028<script>\",\"version\":1}", hostile);
        assertFalse(hostile.contains("\n"), "a data: line must be a single line");
    }
}
