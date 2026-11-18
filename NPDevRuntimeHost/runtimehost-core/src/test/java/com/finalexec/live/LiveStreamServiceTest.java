package com.finalexec.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** FORM P5-T5: what GET /api/live accepts, drops and refuses -- the permission filter is the caller-supplied predicate. */
class LiveStreamServiceTest {

    private static final LiveChangeHub.Sink NOOP = new LiveChangeHub.Sink() {
        @Override
        public void changed(LiveChange change) {
        }

        @Override
        public void heartbeat() {
        }
    };

    private final LiveChangeHub hub = new LiveChangeHub(2, 16, 0, Runnable::run);
    private final LiveStreamService service = new LiveStreamService(hub);

    private int refusal(Runnable call) {
        return assertThrows(LiveStreamService.Refused.class, call::run).status();
    }

    @Test
    void unreadableAndUnknownNamesAreDroppedSilently() {
        Set<String> readable = Set.of("Ride", "RideOffer");
        LiveStreamService.Opened opened = service.open("t", "u", "Ride, Secret ,RideOffer,Nope,Ride", readable::contains, NOOP);
        assertEquals(List.of("Ride", "RideOffer"), opened.concepts());
        List<LiveChange> seen = new ArrayList<>();
        LiveChangeHub.Sink sink = new LiveChangeHub.Sink() {
            @Override
            public void changed(LiveChange change) {
                seen.add(change);
            }

            @Override
            public void heartbeat() {
            }
        };
        service.open("t", "v", "Ride,Secret", readable::contains, sink);
        hub.publish("t", "Secret", "1", 1L); // a concept that was dropped never produces an event, even if it exists
        hub.publish("t", "Ride", "2", 1L);
        assertEquals(List.of("2"), seen.stream().map(LiveChange::id).toList());
    }

    @Test
    void aPredicateThatThrowsMeansNotReadable() {
        LiveStreamService.Opened opened = service.open("t", "u", "A,B", name -> {
            if (name.equals("A")) {
                throw new IllegalArgumentException("unknown concept");
            }
            return true;
        }, NOOP);
        assertEquals(List.of("B"), opened.concepts());
    }

    @Test
    void nothingReadableIs403_andOpensNoStream() {
        assertEquals(403, refusal(() -> service.open("t", "u", "A,B", name -> false, NOOP)));
        assertEquals(0, hub.subscriberCount());
    }

    @Test
    void missingOrMalformedConceptsIs400() {
        assertEquals(400, refusal(() -> service.open("t", "u", null, name -> true, NOOP)));
        assertEquals(400, refusal(() -> service.open("t", "u", " , ,", name -> true, NOOP)));
        assertEquals(400, refusal(() -> service.open("t", "u", "Ride,a b", name -> true, NOOP)));
        assertEquals(400, refusal(() -> service.open("t", "u", "Ride;DROP", name -> true, NOOP)));
        assertEquals(400, refusal(() -> service.open("t", "u", "../etc", name -> true, NOOP)));
        assertEquals(400, refusal(() -> service.open("t", "u", "A".repeat(65), name -> true, NOOP)));
        assertEquals(0, hub.subscriberCount());
    }

    @Test
    void atMostTenConceptsPerStream() {
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("C" + i);
        }
        assertEquals(10, service.open("t", "u", String.join(",", ten), name -> true, NOOP).concepts().size());
        ten.add("C10");
        assertEquals(400, refusal(() -> service.open("t", "u2", String.join(",", ten), name -> true, NOOP)));
        // repeats do not count twice
        assertEquals(1, service.open("t", "u3", "C0,C0,c0".replace("c0", "C0"), name -> true, NOOP).concepts().size());
    }

    @Test
    void theStreamOverTheUserCapIs429_andClosingFreesASlot() {
        LiveStreamService.Opened one = service.open("t", "u", "Ride", name -> true, NOOP);
        service.open("t", "u", "Ride", name -> true, NOOP);
        assertEquals(429, refusal(() -> service.open("t", "u", "Ride", name -> true, NOOP)));
        one.subscription().close();
        assertTrue(service.open("t", "u", "Ride", name -> true, NOOP).subscription() != null);
    }
}
