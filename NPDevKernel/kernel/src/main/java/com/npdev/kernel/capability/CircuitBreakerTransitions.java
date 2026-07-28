package com.npdev.kernel.capability;

/**
 * The circuit breaker's failure transition rule, as a pure function.
 *
 * <p><b>Why this is not simply a private method on KernelRunner (REG-37).</b> It used to be. The
 * orchestration read the current state, computed the next one, and wrote it back -- a plain
 * get-then-put across two store round-trips with nothing serialising them. Under genuinely concurrent
 * failures, which is precisely the load a circuit breaker exists for, two callers both read
 * {@code failures = k} and both write {@code k + 1}: the counter undercounts and the circuit opens
 * late, or never.</p>
 *
 * <p>Making that atomic means the read, the decision and the write have to happen inside one critical
 * section owned by the store -- {@code ConcurrentHashMap.compute} in-process, {@code SELECT ... FOR
 * UPDATE} in a transaction on JDBC. The <em>decision</em> therefore has to be callable from inside
 * each store, which is what this class is for. It stays a pure function of
 * {@code (current, now, thresholds)} so both backends provably apply the same rule; a store that
 * re-implemented the transition itself would be free to drift from the other one.</p>
 *
 * @see com.npdev.kernel.ports.CircuitBreakerStateStore#recordFailure
 */
public final class CircuitBreakerTransitions {

    private CircuitBreakerTransitions() {
    }

    /**
     * The state a breaker moves to when one circuit-eligible failure is recorded against it.
     *
     * @param current            state before this failure; {@code null} means "no row yet", treated
     *                           as closed with zero failures
     * @param nowMs              wall clock for this failure
     * @param openAfterFailures  consecutive failures that open the circuit (floored at 1)
     * @param openMs             how long the circuit stays open (floored at 0)
     */
    public static CircuitBreakerState afterFailure(
            CircuitBreakerState current,
            long nowMs,
            int openAfterFailures,
            long openMs
    ) {
        int failures = current == null ? 1 : Math.max(1, current.consecutiveFailures() + 1);
        int effectiveFailureThreshold = Math.max(1, openAfterFailures);
        long effectiveOpenMs = Math.max(0L, openMs);
        boolean wasHalfOpen = current != null && current.state() == CircuitState.HALF_OPEN;

        // A failure during a half-open trial re-opens immediately: the trial WAS the test, and it
        // failed, so there is nothing left to wait for.
        if (failures >= effectiveFailureThreshold || wasHalfOpen) {
            return new CircuitBreakerState(CircuitState.OPEN, failures, nowMs, nowMs, nowMs + effectiveOpenMs, 0);
        }
        return new CircuitBreakerState(CircuitState.CLOSED, failures, 0L, nowMs, 0L, 0);
    }
}
