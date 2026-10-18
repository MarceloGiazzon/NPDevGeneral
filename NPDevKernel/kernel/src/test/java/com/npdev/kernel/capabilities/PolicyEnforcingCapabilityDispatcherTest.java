package com.npdev.kernel.capabilities;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RUN-1: a capability call outside a flow obeys the operation's declared policy. */
class PolicyEnforcingCapabilityDispatcherTest {

    private static final CapabilityCall CALL = new CapabilityCall(
            "crm", "HttpConnector", "http", "createLead", List.of("x"), "corr-1", null);
    private static final Map<String, Object> STATE = Map.of("tenantId", "t1");

    private static CapabilityExecutionPolicy policy(
            int attempts, long delayMs, long timeoutMs, int circuitAfter, long circuitMs) {
        return new CapabilityExecutionPolicy(attempts, delayMs, timeoutMs, circuitAfter, circuitMs, 0, null, null);
    }

    private static final class MapStore implements CircuitBreakerStateStore {
        private final Map<CapabilityOpKey, CircuitBreakerState> states = new ConcurrentHashMap<>();

        @Override
        public CircuitBreakerState get(CapabilityOpKey key) {
            return states.getOrDefault(key, CircuitBreakerState.closed());
        }

        @Override
        public void put(CapabilityOpKey key, CircuitBreakerState state) {
            states.put(key, state);
        }

        @Override
        public void reset(CapabilityOpKey key) {
            states.remove(key);
        }
    }

    @Test
    void slowCallTimesOutAtTheDeclaredDeadline() {
        CapabilityDispatcher slow = (call, state) -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CapabilityResult.success("late");
        };
        PolicyEnforcingCapabilityDispatcher dispatcher =
                new PolicyEnforcingCapabilityDispatcher(slow, c -> policy(1, 0, 100, 0, 0), new MapStore());

        long started = System.currentTimeMillis();
        CapabilityResult result = dispatcher.invoke(CALL, STATE);

        assertFalse(result.ok());
        assertEquals("CAPABILITY_TIMEOUT", result.error().code());
        assertEquals(CapabilityErrorKind.TIMEOUT, result.error().kind());
        assertTrue(System.currentTimeMillis() - started < 1_500, "must not wait for the slow call");
    }

    @Test
    void transientFailuresAreRetriedUntilTheAttemptsRunOut() {
        AtomicInteger calls = new AtomicInteger();
        CapabilityDispatcher flaky = (call, state) -> calls.incrementAndGet() < 3
                ? CapabilityResult.failure("BLIP", "blip", CapabilityErrorKind.TRANSIENT, Map.of())
                : CapabilityResult.success("ok");
        PolicyEnforcingCapabilityDispatcher dispatcher =
                new PolicyEnforcingCapabilityDispatcher(flaky, c -> policy(3, 1, 0, 0, 0), new MapStore());

        CapabilityResult result = dispatcher.invoke(CALL, STATE);

        assertTrue(result.ok());
        assertEquals("ok", result.value());
        assertEquals(3, calls.get());
    }

    @Test
    void permanentFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        CapabilityDispatcher broken = (call, state) -> {
            calls.incrementAndGet();
            return CapabilityResult.failure("NOPE", "nope", CapabilityErrorKind.PERMANENT, Map.of());
        };
        PolicyEnforcingCapabilityDispatcher dispatcher =
                new PolicyEnforcingCapabilityDispatcher(broken, c -> policy(3, 1, 0, 0, 0), new MapStore());

        CapabilityResult result = dispatcher.invoke(CALL, STATE);

        assertFalse(result.ok());
        assertEquals("NOPE", result.error().code());
        assertEquals(1, calls.get());
    }

    @Test
    void circuitOpensAfterTheDeclaredFailuresAndThenShortCircuits() {
        AtomicInteger calls = new AtomicInteger();
        CapabilityDispatcher down = (call, state) -> {
            calls.incrementAndGet();
            return CapabilityResult.failure("DOWN", "down", CapabilityErrorKind.TRANSIENT, Map.of());
        };
        MapStore store = new MapStore();
        PolicyEnforcingCapabilityDispatcher dispatcher =
                new PolicyEnforcingCapabilityDispatcher(down, c -> policy(1, 0, 0, 5, 60_000), store);

        for (int i = 0; i < 5; i++) {
            assertEquals("DOWN", dispatcher.invoke(CALL, STATE).error().code());
        }
        CapabilityResult sixth = dispatcher.invoke(CALL, STATE);

        assertEquals("CAPABILITY_CIRCUIT_OPEN", sixth.error().code());
        assertEquals(CapabilityErrorKind.TRANSIENT, sixth.error().kind());
        assertEquals(5, calls.get(), "the sixth call must not reach the delegate");
    }

    @Test
    void anotherTenantHasItsOwnCircuit() {
        CapabilityDispatcher down = (call, state) ->
                CapabilityResult.failure("DOWN", "down", CapabilityErrorKind.TRANSIENT, Map.of());
        PolicyEnforcingCapabilityDispatcher dispatcher =
                new PolicyEnforcingCapabilityDispatcher(down, c -> policy(1, 0, 0, 2, 60_000), new MapStore());
        dispatcher.invoke(CALL, STATE);
        dispatcher.invoke(CALL, STATE);
        assertEquals("CAPABILITY_CIRCUIT_OPEN", dispatcher.invoke(CALL, STATE).error().code());

        assertEquals("DOWN", dispatcher.invoke(CALL, Map.of("tenantId", "t2")).error().code());
    }

    @Test
    void successResetsTheCircuitCount() {
        AtomicInteger calls = new AtomicInteger();
        CapabilityDispatcher dispatcher1 = (call, state) -> calls.incrementAndGet() == 2
                ? CapabilityResult.success("ok")
                : CapabilityResult.failure("DOWN", "down", CapabilityErrorKind.TRANSIENT, Map.of());
        PolicyEnforcingCapabilityDispatcher dispatcher =
                new PolicyEnforcingCapabilityDispatcher(dispatcher1, c -> policy(1, 0, 0, 2, 60_000), new MapStore());

        dispatcher.invoke(CALL, STATE);   // failure 1
        dispatcher.invoke(CALL, STATE);   // success -> reset
        dispatcher.invoke(CALL, STATE);   // failure 1 again
        assertEquals("DOWN", dispatcher.invoke(CALL, STATE).error().code());   // failure 2 -> opens now
        assertEquals("CAPABILITY_CIRCUIT_OPEN", dispatcher.invoke(CALL, STATE).error().code());
    }

    @Test
    void noPolicyKeepsTheCallWorkingWithoutALimitOnTheHappyPath() {
        PolicyEnforcingCapabilityDispatcher dispatcher = new PolicyEnforcingCapabilityDispatcher(
                (call, state) -> CapabilityResult.success("fine"), c -> null, null);

        assertEquals("fine", dispatcher.invoke(CALL, STATE).value());
    }

    @Test
    void anUndeclaredDeadlineKeepsTheCallOnTheCallersThread() {
        // A worker thread would leave the caller's ambient transaction behind; no declared timeoutMs,
        // no hop (retry and circuit still apply).
        Thread[] seen = new Thread[1];
        PolicyEnforcingCapabilityDispatcher dispatcher = new PolicyEnforcingCapabilityDispatcher((call, state) -> {
            seen[0] = Thread.currentThread();
            return CapabilityResult.success("ok");
        }, c -> policy(1, 0, 0, 0, 0), new MapStore());

        assertTrue(dispatcher.invoke(CALL, STATE).ok());
        assertEquals(Thread.currentThread(), seen[0]);
    }

    @Test
    void aDeclaredDeadlineRunsTheCallOnAWorkerThread() {
        Thread[] seen = new Thread[1];
        PolicyEnforcingCapabilityDispatcher dispatcher = new PolicyEnforcingCapabilityDispatcher((call, state) -> {
            seen[0] = Thread.currentThread();
            return CapabilityResult.success("ok");
        }, c -> policy(1, 0, 5_000, 0, 0), new MapStore());

        assertTrue(dispatcher.invoke(CALL, STATE).ok());
        assertFalse(Thread.currentThread() == seen[0]);
    }
}
