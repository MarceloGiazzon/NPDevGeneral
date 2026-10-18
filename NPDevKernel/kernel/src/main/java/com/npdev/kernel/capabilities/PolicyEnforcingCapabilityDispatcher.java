package com.npdev.kernel.capabilities;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityError;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitState;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.CircuitBreakerStateStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * RUN-1: gives capability calls made OUTSIDE a flow (a procedure's {@code callCapability} step, an
 * orchestration handler) the same safety a flow {@code capabilityCall} already has -- a deadline,
 * bounded retry of transient failures and the shared circuit breaker -- without touching
 * {@code DefaultProcedureExecutor}: the host wraps the dispatcher it already hands the executor.
 *
 * <p>The rules are KernelRunner's ({@code invokeCapabilityWithPolicy}), copied rather than called
 * into -- KernelRunner is the flow engine and procedure code must not depend on it:</p>
 * <ul>
 *   <li>{@code retryCount} is the number of attempts in total (always &gt;= 1), not retries after
 *       the first; only TRANSIENT, TIMEOUT and RATE_LIMIT failures are retried, after a constant
 *       {@code retryDelayMs} (the flow engine's effective backoff when no host override is set);</li>
 *   <li>{@code timeoutMs} is one deadline for the whole call including retries;</li>
 *   <li>the circuit is keyed {@code (tenant, capability, operation)} exactly like a flow's, so a
 *       flow and a procedure calling the same operation trip and share ONE breaker; the same
 *       failure kinds count against it, a success resets it.</li>
 * </ul>
 *
 * <p>Out of scope: the bulkhead (it stays flow-only) and idempotency-key caching.</p>
 *
 * <p>A deadline needs a worker thread, and a worker thread leaves the caller's ambient transaction
 * behind -- which a procedure or an orchestration action may be running inside. So the call only
 * hops threads when the operation DECLARES a {@code timeoutMs} and the delegate says its target is
 * not already bounded ({@link CapabilityDispatcher#requiresBoundedAsyncDispatch}); an undeclared
 * deadline keeps the call on the caller's thread (retry and circuit still apply), exactly as such
 * calls behaved before this decorator existed. Unlike a flow step there is no 10-minute backstop
 * here for that reason.</p>
 */
public final class PolicyEnforcingCapabilityDispatcher implements CapabilityDispatcher {

    private static final int CIRCUIT_FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_DURATION_MS = 30_000L;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "npdev-procedure-capability-" + THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final CapabilityDispatcher delegate;
    /** Resolves the model-declared policy of (capability, operation); null or defaults when none. */
    private final Function<CapabilityCall, CapabilityExecutionPolicy> policyFor;
    private final CircuitBreakerStateStore circuits;

    public PolicyEnforcingCapabilityDispatcher(
            CapabilityDispatcher delegate,
            Function<CapabilityCall, CapabilityExecutionPolicy> policyFor,
            CircuitBreakerStateStore circuits
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policyFor = Objects.requireNonNull(policyFor, "policyFor");
        this.circuits = circuits == null ? CircuitBreakerStateStore.noop() : circuits;
    }

    @Override
    public boolean requiresBoundedAsyncDispatch(CapabilityCall call) {
        return delegate.requiresBoundedAsyncDispatch(call);
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        CapabilityExecutionPolicy policy = policyFor.apply(call);
        if (policy == null) {
            policy = CapabilityExecutionPolicy.defaults();
        }
        long timeoutMs = policy.timeoutMs();   // 0 = no declared deadline: stay on the caller's thread
        int maxAttempts = Math.max(1, policy.retryCount());
        int openAfterFailures = policy.circuitOpenAfterFailures() > 0
                ? policy.circuitOpenAfterFailures() : CIRCUIT_FAILURE_THRESHOLD;
        long openMs = policy.circuitOpenMs() > 0 ? policy.circuitOpenMs() : CIRCUIT_OPEN_DURATION_MS;
        CapabilityOpKey key = new CapabilityOpKey(tenantOf(contextState), call.capability(), call.operation());

        CircuitBreakerState state = circuits.get(key);
        if (!gate(key, state)) {
            return CapabilityResult.failure(
                    "CAPABILITY_CIRCUIT_OPEN",
                    "Circuit breaker is open for " + call.capability() + "." + call.operation(),
                    CapabilityErrorKind.TRANSIENT,
                    details(call, "circuitState", state == null ? CircuitState.OPEN.name() : state.state().name()));
        }

        long startedAt = System.currentTimeMillis();
        CapabilityResult last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long remaining = timeoutMs > 0 ? timeoutMs - (System.currentTimeMillis() - startedAt) : 0;
            if (timeoutMs > 0 && remaining <= 0) {
                last = timeoutResult(call, attempt, timeoutMs);
                break;
            }
            CapabilityResult result = invokeOnce(call, contextState, remaining, timeoutMs, attempt);
            if (result.ok()) {
                circuits.reset(key);
                return result;
            }
            CapabilityError error = result.error();
            if (error == null) {
                last = result;
                break;
            }
            CapabilityErrorKind kind = policy.applyFailureClassification(error.kind());
            if (kind == null) {
                kind = error.kind();
            }
            if (kind != error.kind()) {
                error = new CapabilityError(error.code(), error.message(), kind, error.details());
                result = CapabilityResult.failure(error);
            }
            last = result;
            if (!countsAgainstCircuit(kind) || attempt >= maxAttempts) {
                break;
            }
            if (!sleep(policy.retryDelayMs())) {
                break;
            }
        }
        if (last == null) {
            last = CapabilityResult.failure("CAPABILITY_POLICY_EXHAUSTED",
                    "Capability invocation exhausted retry policy", CapabilityErrorKind.PERMANENT, details(call));
        }
        CapabilityError finalError = last.error();
        if (finalError != null) {
            if (countsAgainstCircuit(finalError.kind())) {
                circuits.recordFailure(key, System.currentTimeMillis(), openAfterFailures, openMs);
            } else {
                CircuitBreakerState current = circuits.get(key);
                if (current != null && current.state() != CircuitState.CLOSED) {
                    circuits.reset(key);
                }
            }
        }
        return last;
    }

    /** Same gate KernelRunner applies: OPEN refuses until due, then lets exactly one half-open trial through. */
    private boolean gate(CapabilityOpKey key, CircuitBreakerState state) {
        CircuitBreakerState current = state == null ? CircuitBreakerState.closed() : state;
        if (current.state() == CircuitState.OPEN) {
            if (System.currentTimeMillis() < current.halfOpenAllowedAtMs()) {
                return false;
            }
            circuits.put(key, new CircuitBreakerState(CircuitState.HALF_OPEN, current.consecutiveFailures(),
                    current.openedAtMs(), current.lastFailureAtMs(), current.halfOpenAllowedAtMs(), 0));
            return true;
        }
        if (current.state() == CircuitState.HALF_OPEN) {
            if (current.halfOpenTrialCount() >= 1) {
                return false;
            }
            circuits.put(key, new CircuitBreakerState(CircuitState.HALF_OPEN, current.consecutiveFailures(),
                    current.openedAtMs(), current.lastFailureAtMs(), current.halfOpenAllowedAtMs(), 1));
        }
        return true;
    }

    private CapabilityResult invokeOnce(
            CapabilityCall call, Map<String, Object> contextState, long remainingMs, long timeoutMs, int attempt) {
        if (timeoutMs <= 0 || !delegate.requiresBoundedAsyncDispatch(call)) {
            return invokeSafely(call, contextState);
        }
        CompletableFuture<CapabilityResult> future =
                CompletableFuture.supplyAsync(() -> invokeSafely(call, contextState), EXECUTOR);
        try {
            return future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return timeoutResult(call, attempt, timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CapabilityResult.failure("CAPABILITY_DISPATCHER_INTERRUPTED",
                    "Capability invocation interrupted", CapabilityErrorKind.TRANSIENT, details(call));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return CapabilityResult.failure("CAPABILITY_DISPATCHER_EXCEPTION",
                    cause.getMessage() == null ? "Capability dispatcher threw " + cause.getClass().getSimpleName() : cause.getMessage(),
                    CapabilityErrorKind.PERMANENT, details(call));
        }
    }

    private CapabilityResult invokeSafely(CapabilityCall call, Map<String, Object> contextState) {
        try {
            return delegate.invoke(call, contextState);
        } catch (RuntimeException e) {
            return CapabilityResult.failure("CAPABILITY_DISPATCHER_EXCEPTION",
                    e.getMessage() == null ? "Capability dispatcher threw " + e.getClass().getSimpleName() : e.getMessage(),
                    e instanceof IllegalArgumentException ? CapabilityErrorKind.CONTRACT : CapabilityErrorKind.PERMANENT,
                    details(call));
        }
    }

    private static boolean countsAgainstCircuit(CapabilityErrorKind kind) {
        return kind == CapabilityErrorKind.TRANSIENT
                || kind == CapabilityErrorKind.TIMEOUT
                || kind == CapabilityErrorKind.RATE_LIMIT;
    }

    private static boolean sleep(long delayMs) {
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static CapabilityResult timeoutResult(CapabilityCall call, int attempt, long timeoutMs) {
        return CapabilityResult.failure("CAPABILITY_TIMEOUT",
                "Capability invocation timed out after " + timeoutMs + "ms",
                CapabilityErrorKind.TIMEOUT, details(call, "attempt", attempt, "timeoutMs", timeoutMs));
    }

    private static String tenantOf(Map<String, Object> contextState) {
        Object tenant = contextState == null ? null : contextState.get("tenantId");
        String id = tenant == null ? "" : String.valueOf(tenant).trim();
        return id.isEmpty() ? "default" : id;
    }

    private static Map<String, Object> details(CapabilityCall call, Object... extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capability", call.capability());
        out.put("operation", call.operation());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            out.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return out;
    }
}
