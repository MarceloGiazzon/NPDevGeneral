package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-340: an await's {@code match.payload} compares a value read from the PARKED flow's state (which has been
 * through JSON: ids are strings, whole numbers are Long) with the event's payload. For a synchronous resume by an
 * {@code emitEvent} step that payload is still the live in-memory map, so the same id arrives as a {@code UUID}.
 * {@code Objects.equals(String, UUID)} is false, and the parked flow stayed parked until the scheduled sweep re-read
 * the event from the store -- 20 s or more later (found with ride-hailing's offer wait).
 */
class KernelRunnerAwaitPayloadMatchTest {

    private static EventEnvelope event(Map<String, Object> payload) {
        return new EventEnvelope("e-1", "RideOfferAnswered", 1L, payload, "corr", "cause", "AcceptRideOffer", 0, "dev", "driver1");
    }

    private static Map<String, Object> parkedState(Object id) {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", id);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("created", created);
        return state;
    }

    @Test
    void aLiveUuidInTheEventMatchesTheStringIdInTheParkedState() {
        UUID ride = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rideId", ride);
        payload.put("answer", "ACCEPT");

        assertTrue(KernelRunner.matchesAwaitPayload(
                Map.of("rideId", "$created.id"), event(payload), parkedState(ride.toString()), null));
        assertTrue(KernelRunner.matchesAwaitPayload(
                Map.of("rideId", "$created.id"), event(payload), parkedState(ride.toString().toUpperCase()), null),
                "the canonical text of an id is case-insensitive");
    }

    @Test
    void theSameStringOnBothSidesStillMatchesAndADifferentIdStillDoesNot() {
        UUID ride = UUID.randomUUID();
        assertTrue(KernelRunner.matchesAwaitPayload(
                Map.of("rideId", "$created.id"), event(Map.of("rideId", ride.toString())), parkedState(ride.toString()), null));
        assertFalse(KernelRunner.matchesAwaitPayload(
                Map.of("rideId", "$created.id"), event(Map.of("rideId", UUID.randomUUID())), parkedState(ride.toString()), null));
        assertFalse(KernelRunner.matchesAwaitPayload(
                Map.of("rideId", "$created.id"), event(Map.of("answer", "ACCEPT")), parkedState(ride.toString()), null),
                "a payload without the key never matches");
    }

    @Test
    void numbersMatchByValueNotByBoxedType() {
        assertTrue(KernelRunner.awaitValuesEqual(3L, 3));
        assertTrue(KernelRunner.awaitValuesEqual(new BigDecimal("3.0"), 3));
        assertTrue(KernelRunner.awaitValuesEqual(3.5d, new BigDecimal("3.50")));
        assertFalse(KernelRunner.awaitValuesEqual(3L, 4));
        assertFalse(KernelRunner.awaitValuesEqual(Double.NaN, 3), "a value that is not a finite number never equals one");
    }

    @Test
    void aStringIsNeverEqualToANumberAndNullOnlyEqualsNull() {
        assertFalse(KernelRunner.awaitValuesEqual("3", 3), "no guessing between text and numbers");
        assertFalse(KernelRunner.awaitValuesEqual(null, "x"));
        assertFalse(KernelRunner.awaitValuesEqual("x", null));
        assertTrue(KernelRunner.awaitValuesEqual(null, null));
    }
}
