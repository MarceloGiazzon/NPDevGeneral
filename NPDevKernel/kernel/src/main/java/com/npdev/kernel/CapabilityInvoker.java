package com.npdev.kernel;

import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CapabilityPolicyOverride;
import com.npdev.kernel.capability.CapabilityPolicyOverrides;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitState;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.KernelRunner.EffectiveCapabilityPolicy;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.ports.MetricsSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.npdev.kernel.KernelRunner.LOG;
import static com.npdev.kernel.KernelRunner.nowEpochMillis;
import static com.npdev.kernel.KernelRunner.normalizeTenantOrDefault;

/**
 * CARVE-4 (pure mechanical extraction, behaviour-preserving): the capability-invocation-with-policy
 * machinery split out of {@link KernelRunner} verbatim -- effective-policy resolution (override >
 * model > hardcoded default), the circuit breaker gate, the bulkhead, the idempotency cache, the
 * retry/timeout loop, the capability metrics and the dedicated timeout executor. Method bodies are
 * byte-identical to what they were in KernelRunner; the collaborators they used as KernelRunner
 * fields are the same-named fields of this instance. {@link KernelRunner} owns one instance and
 * keeps delegating {@code resolveEffectiveCapabilityPolicy} / {@code invokeCapabilityWithPolicy} /
 * {@code reclassifyIntegrityViolation}, so its public and package API is unchanged. Flat sibling in
 * {@code com.npdev.kernel} for the same reason {@code ResumeCoordinator} and {@code
 * CompensationRunner} are: the collaborators are package-private.
 */
final class CapabilityInvoker {

    private final CapabilityDispatcher capabilityDispatcher;
    private final CircuitBreakerStateStore circuitBreakerStateStore;
    private final BulkheadStore bulkheadStore;
    private final IdempotencyStore idempotencyStore;
    private final CapabilityPolicyOverrides capabilityPolicyOverrides;
    private final JsonCodec jsonCodec;
    private final MetricsSink metricsSink;
    private final ThreadLocal<String> currentFlowContext;

    private static final int CIRCUIT_FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_DURATION_MS = 30_000L;
    private static final int BULKHEAD_MAX_CONCURRENT = 10;

    // R2.6 (RUN-4): the kernel-wide capability timeout backstop. Before this,
    // CapabilityExecutionPolicy.defaults() returned timeoutMs=0 and a model that declared a
    // capabilityPolicy without a timeout got 0 too, so invokeCapabilityOnce took the synchronous
    // branch and a capability adapter that never returned hung the calling thread forever. The
    // adapter-level deadlines shipped for RUN-4 cover the two NPDev network adapters only -- a
    // custom or third-party CapabilityAdapter still has no deadline at all, which is what this
    // backstop is for. Resolved with the same "0 means unset -> hardcoded constant" precedence
    // resolveEffectiveCapabilityPolicy already uses for circuit/bulkhead, so a host-supplied
    // override of exactly 0 still disables the timeout (and restores the old synchronous path).
    //
    // 10 minutes is deliberately far above any legitimate capability duration rather than a tuned
    // SLA: external-ai-http alone can legitimately spend maxRetries(2)+1 attempts x 120s request
    // timeout plus backoff, so anything under ~6 minutes would turn a hang backstop into a
    // functional limit on a working adapter.
    private static final long DEFAULT_CAPABILITY_TIMEOUT_MS = CapabilityExecutionPolicy.DEFAULT_TIMEOUT_MS;

    private static final AtomicInteger CAPABILITY_THREAD_SEQUENCE = new AtomicInteger();

    // R2.6 (RUN-4): the timeout branch used to be CompletableFuture.supplyAsync(supplier) with no
    // Executor, which per the JDK contract runs on ForkJoinPool.commonPool(). That pool's workers
    // are shared, pre-existing and reused, so they do not carry the calling thread's thread-local
    // state -- and this kernel HAS such state on the capability call stack: currentFlowContext is
    // set for the whole flow-step loop (see execute) and read by assertCrudInvariantPathAllowed,
    // which is what stops the deprecated CRUD invariant path from being re-entered during flow
    // execution. On a commonPool worker that read returns null, so the guard silently stops
    // guarding instead of throwing. Cancelling on timeout also interrupts a SHARED pool thread.
    // A dedicated cached pool of daemon threads fixes both: it is sized by demand exactly as the
    // synchronous path was (one thread per in-flight capability), the threads are named so a stack
    // dump is readable, daemon so they never hold the JVM open, and interrupting one on timeout
    // affects nothing but that capability.
    private static final ExecutorService CAPABILITY_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "npdev-capability-" + CAPABILITY_THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private static final int IDEMPOTENCY_RESULT_MAX_CHARS = 16_384;

    CapabilityInvoker(
            CapabilityDispatcher capabilityDispatcher,
            CircuitBreakerStateStore circuitBreakerStateStore,
            BulkheadStore bulkheadStore,
            IdempotencyStore idempotencyStore,
            CapabilityPolicyOverrides capabilityPolicyOverrides,
            JsonCodec jsonCodec,
            MetricsSink metricsSink,
            ThreadLocal<String> currentFlowContext
    ) {
        this.capabilityDispatcher = capabilityDispatcher;
        this.circuitBreakerStateStore = circuitBreakerStateStore;
        this.bulkheadStore = bulkheadStore;
        this.idempotencyStore = idempotencyStore;
        this.capabilityPolicyOverrides = capabilityPolicyOverrides;
        this.jsonCodec = jsonCodec;
        this.metricsSink = metricsSink;
        this.currentFlowContext = currentFlowContext;
    }

    private static CapabilityErrorKind classifyCapabilityException(RuntimeException runtimeException) {
        if (runtimeException instanceof IllegalArgumentException
                || runtimeException instanceof ClassCastException
                || runtimeException instanceof UnsupportedOperationException) {
            return CapabilityErrorKind.CONTRACT;
        }
        String message = runtimeException.getMessage() == null ? "" : runtimeException.getMessage().toLowerCase();
        String typeName = runtimeException.getClass().getName().toLowerCase();
        if (typeName.contains("auth")
                || typeName.contains("forbidden")
                || message.contains("unauthorized")
                || message.contains("forbidden")
                || message.contains("access denied")) {
            return CapabilityErrorKind.AUTH;
        }
        if (typeName.contains("rate")
                || message.contains("rate limit")
                || message.contains("too many requests")) {
            return CapabilityErrorKind.RATE_LIMIT;
        }
        if (typeName.contains("timeout")
                || message.contains("timeout")) {
            return CapabilityErrorKind.TIMEOUT;
        }
        if (typeName.contains("transient")
                || typeName.contains("temporar")) {
            return CapabilityErrorKind.TRANSIENT;
        }
        return CapabilityErrorKind.PERMANENT;
    }

    EffectiveCapabilityPolicy resolveEffectiveCapabilityPolicy(
            FlowStepDefinition step,
            CapabilityExecutionPolicy basePolicy,
            String tenantId
    ) {
        CapabilityExecutionPolicy safeBase = basePolicy == null
                ? CapabilityExecutionPolicy.defaults()
                : basePolicy;
        CapabilityPolicyOverride override = capabilityPolicyOverrides.find(
                        step.getCapability(),
                        step.getOperation()
                )
                .orElse(CapabilityPolicyOverride.empty());

        int retryMaxAttempts = positiveOrDefault(override.retryMaxAttempts(), safeBase.retryCount());
        long retryBaseDelayMs = nonNegativeOrDefault(override.retryBaseDelayMs(), safeBase.retryDelayMs());
        long retryMaxDelayMs = nonNegativeOrDefault(
                override.retryMaxDelayMs(),
                Math.max(retryBaseDelayMs, safeBase.retryDelayMs())
        );
        // R2.6 (RUN-4): 0 means unset here exactly as it does for circuit/bulkhead below, so an
        // undeclared timeout -- whether from CapabilityExecutionPolicy.defaults() or from a model
        // that declared a capabilityPolicy but no timeoutMs -- resolves to the hardcoded backstop
        // instead of "no deadline at all". A host-supplied override of exactly 0 still wins and
        // disables the timeout, which is the escape hatch back to the synchronous path.
        long timeoutMsBase = safeBase.timeoutMs() > 0 ? safeBase.timeoutMs() : DEFAULT_CAPABILITY_TIMEOUT_MS;
        long timeoutMs = nonNegativeOrDefault(override.timeoutMs(), timeoutMsBase);
        // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5 / capabilityPolicy): a declared model
        // policy's circuitOpenAfterFailures/circuitOpenMs/bulkheadMaxConcurrent now sit BETWEEN the
        // host-supplied override and the hardcoded constant -- the same 3-tier precedence retry/
        // timeout already have (override > model > hardcoded default), previously missing entirely
        // for these three: every capability silently used the hardcoded constants regardless of what
        // the model declared.
        int circuitAfterFailuresBase = safeBase.circuitOpenAfterFailures() > 0
                ? safeBase.circuitOpenAfterFailures() : CIRCUIT_FAILURE_THRESHOLD;
        long circuitOpenMsBase = safeBase.circuitOpenMs() > 0 ? safeBase.circuitOpenMs() : CIRCUIT_OPEN_DURATION_MS;
        int bulkheadMaxConcurrentBase = safeBase.bulkheadMaxConcurrent() > 0
                ? safeBase.bulkheadMaxConcurrent() : BULKHEAD_MAX_CONCURRENT;
        int circuitAfterFailures = positiveOrDefault(override.circuitOpenAfterFailures(), circuitAfterFailuresBase);
        long circuitOpenMs = nonNegativeOrDefault(override.circuitOpenMs(), circuitOpenMsBase);
        int bulkheadMaxConcurrent = positiveOrDefault(override.bulkheadMaxConcurrent(), bulkheadMaxConcurrentBase);
        boolean cacheIdempotencyFailures = override.cacheIdempotencyFailures() != null
                && override.cacheIdempotencyFailures();

        return new EffectiveCapabilityPolicy(
                retryMaxAttempts,
                retryBaseDelayMs,
                Math.max(retryBaseDelayMs, retryMaxDelayMs),
                timeoutMs,
                circuitAfterFailures,
                circuitOpenMs,
                bulkheadMaxConcurrent,
                cacheIdempotencyFailures
        );
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    private static long nonNegativeOrDefault(Long value, long fallback) {
        if (value == null || value < 0L) {
            return fallback;
        }
        return value;
    }

    private static long retryDelayForAttempt(EffectiveCapabilityPolicy policy, int attempt) {
        if (policy == null || policy.retryBaseDelayMs() <= 0) {
            return 0L;
        }
        int exponent = Math.max(0, attempt - 1);
        long multiplier;
        if (exponent >= 20) {
            multiplier = 1L << 20;
        } else {
            multiplier = 1L << exponent;
        }
        long computed = policy.retryBaseDelayMs() * multiplier;
        if (computed < 0L) {
            return policy.retryMaxDelayMs();
        }
        return Math.min(computed, Math.max(policy.retryBaseDelayMs(), policy.retryMaxDelayMs()));
    }

    static CapabilityError reclassifyIntegrityViolation(CapabilityError error) {
        if (error == null
                || error.kind() == CapabilityErrorKind.CONTRACT
                || !isIntegrityViolationMessage(error.message())) {
            return error;
        }
        return new CapabilityError(
                error.code(),
                error.message(),
                CapabilityErrorKind.CONTRACT,
                error.details()
        );
    }

    private static boolean isIntegrityViolationMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("referential integrity")
                || normalized.contains("integrity constraint")
                || normalized.contains("referential/constraint violation")
                || normalized.contains("foreign key")
                || normalized.contains("unique index or primary key")
                || normalized.contains("unique constraint");
    }

    CapabilityResult invokeCapabilityWithPolicy(
            FlowStepDefinition step,
            List<Object> args,
            Map<String, Object> state,
            String correlationId,
            String idempotencyKey,
            CapabilityExecutionPolicy policy,
            EffectiveCapabilityPolicy effectivePolicy,
            Map<String, Object> traceInfo,
            String tenantId
    ) {
        long startedAtMs = nowEpochMillis();
        CapabilityOpKey opKey = new CapabilityOpKey(
                normalizeTenantOrDefault(tenantId),
                Objects.requireNonNull(step.getCapability(), "capability is required"),
                Objects.requireNonNull(step.getOperation(), "operation is required")
        );
        String idempotencyState = (idempotencyKey == null || idempotencyKey.isBlank()) ? "NONE" : "MISS";
        String bulkheadState = "NOT_USED";
        int attemptsUsed = 0;
        safeMetricInc("npdev.capability.call", capabilityMetricTags(
                opKey,
                null,
                CircuitState.CLOSED.name(),
                bulkheadState,
                idempotencyState
        ));
        long now = nowEpochMillis();
        CircuitBreakerState circuit = circuitBreakerStateStore.get(opKey);
        String circuitState = circuitStateName(circuit, CircuitState.CLOSED.name());
        updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
        CircuitGate gate = gateCircuit(opKey, circuit, now);
        if (!gate.allowed()) {
            circuitState = currentCircuitState(opKey, CircuitState.OPEN.name());
            updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
            CapabilityResult circuitFailure = circuitOpenFailure(step, opKey, circuit);
            safeMetricFailure(
                    opKey,
                    startedAtMs,
                    circuitFailure.error(),
                    circuitState,
                    bulkheadState,
                    idempotencyState
            );
            return circuitFailure;
        }

        int maxConcurrent = gate.halfOpenTrial() ? 1 : effectivePolicy.bulkheadMaxConcurrent();
        if (!bulkheadStore.tryAcquire(opKey, maxConcurrent, now)) {
            bulkheadState = "REJECTED";
            circuitState = currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name()));
            updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
            CapabilityResult rejected = CapabilityResult.failure(
                    "CAPABILITY_BULKHEAD_FULL",
                    "Capability bulkhead is full for " + step.getCapability() + "." + step.getOperation(),
                    CapabilityErrorKind.TRANSIENT,
                    capabilityDetails(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "tenantId", opKey.tenantId(),
                            "maxConcurrent", maxConcurrent
                    )
            );
            safeMetricFailure(
                    opKey,
                    startedAtMs,
                    rejected.error(),
                    circuitState,
                    bulkheadState,
                    idempotencyState
            );
            return rejected;
        }
        bulkheadState = "ACQUIRED";
        updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);

        try {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Optional<IdempotencyRecord> cachedRecord = idempotencyStore.find(
                        opKey.tenantId(),
                        opKey.capabilityName(),
                        opKey.operationName(),
                        idempotencyKey
                );
                if (cachedRecord.isPresent()) {
                    IdempotencyRecord record = cachedRecord.get();
                    idempotencyState = "HIT";
                    updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
                    if (record.success()) {
                        onCapabilitySuccess(opKey, now);
                        circuitState = currentCircuitState(opKey, CircuitState.CLOSED.name());
                        updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
                        CapabilityResult cachedSuccess = CapabilityResult.success(fromCachedSuccessRecord(record));
                        safeMetricSuccess(
                                opKey,
                                startedAtMs,
                                circuitState,
                                bulkheadState,
                                idempotencyState
                        );
                        return cachedSuccess;
                    }
                    CapabilityResult cachedFailure = fromCachedFailureRecord(step, record);
                    if (cachedFailure != null) {
                        onCapabilityFailure(
                                opKey,
                                cachedFailure.error(),
                                now,
                                effectivePolicy.circuitOpenAfterFailures(),
                                effectivePolicy.circuitOpenMs()
                        );
                        safeMetricFailure(
                                opKey,
                                startedAtMs,
                                cachedFailure.error(),
                                currentCircuitState(opKey, CircuitState.CLOSED.name()),
                                bulkheadState,
                                idempotencyState
                        );
                        return cachedFailure;
                    }
                } else {
                    idempotencyState = "MISS";
                    updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
                }
            }

            long startedAt = nowEpochMillis();
            int maxAttempts = Math.max(1, effectivePolicy.retryMaxAttempts());
            CapabilityResult lastFailure = null;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                attemptsUsed = attempt;
                updateCapabilityTraceInfo(traceInfo, attemptsUsed, circuitState, bulkheadState, idempotencyState);
                long remainingTimeoutMs = remainingTimeout(effectivePolicy.timeoutMs(), startedAt);
                if (effectivePolicy.timeoutMs() > 0 && remainingTimeoutMs <= 0) {
                    lastFailure = timeoutCapabilityResult(step, attempt, effectivePolicy.timeoutMs());
                    break;
                }

                CapabilityResult attemptResult = invokeCapabilityOnce(
                        step,
                        args,
                        state,
                        correlationId,
                        idempotencyKey,
                        remainingTimeoutMs,
                        attempt
                );

                if (attemptResult.ok()) {
                    onCapabilitySuccess(opKey, nowEpochMillis());
                    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        idempotencyStore.saveSuccess(
                                opKey.tenantId(),
                                opKey.capabilityName(),
                                opKey.operationName(),
                                idempotencyKey,
                                encodeIdempotencyResult(attemptResult.value()),
                                nowEpochMillis()
                        );
                    }
                    safeMetricSuccess(
                            opKey,
                            startedAtMs,
                            currentCircuitState(opKey, CircuitState.CLOSED.name()),
                            bulkheadState,
                            idempotencyState
                    );
                    updateCapabilityTraceInfo(
                            traceInfo,
                            attemptsUsed,
                            currentCircuitState(opKey, CircuitState.CLOSED.name()),
                            bulkheadState,
                            idempotencyState
                    );
                    return attemptResult;
                }

                CapabilityResult normalized = normalizeCapabilityFailure(step, attemptResult, attempt);
                CapabilityError error = normalized.error();
                if (error == null) {
                    lastFailure = normalized;
                    break;
                }
                CapabilityErrorKind kind = policy.applyFailureClassification(error.kind());
                if (kind == null) {
                    kind = error.kind();
                }
                if (kind != error.kind()) {
                    error = new CapabilityError(error.code(), error.message(), kind, error.details());
                    normalized = CapabilityResult.failure(error);
                }
                lastFailure = normalized;

                if (isRetryable(kind) && attempt < maxAttempts) {
                    long retryDelayMs = retryDelayForAttempt(effectivePolicy, attempt);
                    if (!sleepForRetry(retryDelayMs)) {
                        lastFailure = CapabilityResult.failure(
                                "CAPABILITY_RETRY_INTERRUPTED",
                                "Capability retry was interrupted",
                                CapabilityErrorKind.TRANSIENT,
                                capabilityDetails(
                                        "capability", step.getCapability(),
                                        "operation", step.getOperation(),
                                        "attempt", attempt,
                                        "maxAttempts", maxAttempts,
                                        "retryDelayMs", retryDelayMs
                                )
                        );
                        break;
                    }
                    continue;
                }
                break;
            }

            if (lastFailure == null) {
                lastFailure = CapabilityResult.failure(
                        "CAPABILITY_POLICY_EXHAUSTED",
                        "Capability invocation exhausted retry policy",
                        CapabilityErrorKind.PERMANENT,
                        capabilityDetails(
                                "capability", step.getCapability(),
                                "operation", step.getOperation(),
                                "retryCount", maxAttempts
                        )
                );
            }
            CapabilityError finalError = lastFailure.error();
            if (finalError != null) {
                onCapabilityFailure(
                        opKey,
                        finalError,
                        nowEpochMillis(),
                        effectivePolicy.circuitOpenAfterFailures(),
                        effectivePolicy.circuitOpenMs()
                );
                if (idempotencyKey != null
                        && !idempotencyKey.isBlank()
                        && shouldCacheFailure(finalError, effectivePolicy.cacheIdempotencyFailures())) {
                    idempotencyStore.saveFailure(
                            opKey.tenantId(),
                            opKey.capabilityName(),
                            opKey.operationName(),
                            idempotencyKey,
                            finalError.kind().name() + ":" + finalError.code(),
                            nowEpochMillis()
                    );
                }
                safeMetricFailure(
                        opKey,
                        startedAtMs,
                        finalError,
                        currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name())),
                        bulkheadState,
                        idempotencyState
                );
                updateCapabilityTraceInfo(
                        traceInfo,
                        attemptsUsed,
                        currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name())),
                        bulkheadState,
                        idempotencyState
                );
            } else {
                safeMetricFailure(
                        opKey,
                        startedAtMs,
                        null,
                        currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name())),
                        bulkheadState,
                        idempotencyState
                );
                updateCapabilityTraceInfo(
                        traceInfo,
                        attemptsUsed,
                        currentCircuitState(opKey, circuitStateName(circuit, CircuitState.CLOSED.name())),
                        bulkheadState,
                        idempotencyState
                );
            }
            return lastFailure;
        } finally {
            bulkheadStore.release(opKey);
        }
    }

    private static void updateCapabilityTraceInfo(
            Map<String, Object> traceInfo,
            int attemptsUsed,
            String circuitState,
            String bulkheadState,
            String idempotencyState
    ) {
        if (traceInfo == null) {
            return;
        }
        traceInfo.put("attemptCount", attemptsUsed);
        traceInfo.put("circuitState", circuitState);
        traceInfo.put("bulkheadState", bulkheadState);
        traceInfo.put("idempotencyState", idempotencyState);
    }

    private CircuitGate gateCircuit(CapabilityOpKey key, CircuitBreakerState state, long now) {
        CircuitBreakerState current = state == null ? CircuitBreakerState.closed() : state;
        if (current.state() == CircuitState.OPEN) {
            if (now < current.halfOpenAllowedAtMs()) {
                return new CircuitGate(false, false);
            }
            CircuitBreakerState halfOpen = new CircuitBreakerState(
                    CircuitState.HALF_OPEN,
                    current.consecutiveFailures(),
                    current.openedAtMs(),
                    current.lastFailureAtMs(),
                    current.halfOpenAllowedAtMs(),
                    0
            );
            circuitBreakerStateStore.put(key, halfOpen);
            return new CircuitGate(true, true);
        }
        if (current.state() == CircuitState.HALF_OPEN) {
            if (current.halfOpenTrialCount() >= 1) {
                return new CircuitGate(false, true);
            }
            CircuitBreakerState trial = new CircuitBreakerState(
                    CircuitState.HALF_OPEN,
                    current.consecutiveFailures(),
                    current.openedAtMs(),
                    current.lastFailureAtMs(),
                    current.halfOpenAllowedAtMs(),
                    1
            );
            circuitBreakerStateStore.put(key, trial);
            return new CircuitGate(true, true);
        }
        return new CircuitGate(true, false);
    }

    private record CircuitGate(
            boolean allowed,
            boolean halfOpenTrial
    ) {
    }

    void safeMetricInc(String name, Map<String, String> tags) {
        try {
            metricsSink.inc(name, tags);
        } catch (RuntimeException ignored) {
            // Metrics must never break execution.
        }
    }

    void safeMetricObserveMs(String name, long durationMs, Map<String, String> tags) {
        try {
            metricsSink.observeMs(name, durationMs, tags);
        } catch (RuntimeException ignored) {
            // Metrics must never break execution.
        }
    }

    private void safeMetricSuccess(
            CapabilityOpKey opKey,
            long startedAtMs,
            String circuitState,
            String bulkheadState,
            String idempotencyState
    ) {
        Map<String, String> tags = capabilityMetricTags(opKey, null, circuitState, bulkheadState, idempotencyState);
        safeMetricInc("npdev.capability.success", tags);
        safeMetricObserveMs("npdev.capability.duration_ms", nowEpochMillis() - startedAtMs, tags);
    }

    private void safeMetricFailure(
            CapabilityOpKey opKey,
            long startedAtMs,
            CapabilityError error,
            String circuitState,
            String bulkheadState,
            String idempotencyState
    ) {
        Map<String, String> tags = capabilityMetricTags(opKey, error, circuitState, bulkheadState, idempotencyState);
        safeMetricInc("npdev.capability.failure", tags);
        safeMetricObserveMs("npdev.capability.duration_ms", nowEpochMillis() - startedAtMs, tags);
    }

    private static Map<String, String> capabilityMetricTags(
            CapabilityOpKey opKey,
            CapabilityError error,
            String circuitState,
            String bulkheadState,
            String idempotencyState
    ) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("tenantId", safeTag(opKey == null ? null : opKey.tenantId(), "default"));
        tags.put("capabilityName", safeTag(opKey == null ? null : opKey.capabilityName(), "<none>"));
        tags.put("operationName", safeTag(opKey == null ? null : opKey.operationName(), "<none>"));
        tags.put("errorKind", error == null || error.kind() == null ? "NONE" : error.kind().name());
        tags.put("circuitState", safeTag(circuitState, CircuitState.CLOSED.name()));
        tags.put("bulkhead", safeTag(bulkheadState, "NOT_USED"));
        tags.put("idempotency", safeTag(idempotencyState, "NONE"));
        return Map.copyOf(tags);
    }

    private String currentCircuitState(CapabilityOpKey opKey, String fallback) {
        try {
            CircuitBreakerState current = circuitBreakerStateStore.get(opKey);
            if (current == null || current.state() == null) {
                return fallback;
            }
            return current.state().name();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String circuitStateName(CircuitBreakerState state, String fallback) {
        if (state == null || state.state() == null) {
            return fallback;
        }
        return state.state().name();
    }

    static String safeTag(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private void onCapabilitySuccess(CapabilityOpKey key, long now) {
        circuitBreakerStateStore.reset(key);
    }

    private void onCapabilityFailure(
            CapabilityOpKey key,
            CapabilityError error,
            long now,
            int circuitOpenAfterFailures,
            long circuitOpenMs
    ) {
        if (error == null) {
            return;
        }
        if (!isCircuitFailure(error.kind())) {
            CircuitBreakerState current = circuitBreakerStateStore.get(key);
            if (current != null && current.state() != CircuitState.CLOSED) {
                circuitBreakerStateStore.reset(key);
            }
            return;
        }

        // REG-37: the increment and the open/closed decision now happen INSIDE the store, under a
        // lock it owns. This used to be get() -> compute here -> put(), which is a read-modify-write
        // race: concurrent failures both read the same count, both write count+1, and the breaker
        // opens late under exactly the load it exists for. The rule itself lives in
        // CircuitBreakerTransitions so both backends provably apply the same one.
        circuitBreakerStateStore.recordFailure(key, now, circuitOpenAfterFailures, circuitOpenMs);
    }

    private static boolean isCircuitFailure(CapabilityErrorKind kind) {
        return kind == CapabilityErrorKind.TRANSIENT
                || kind == CapabilityErrorKind.TIMEOUT
                || kind == CapabilityErrorKind.RATE_LIMIT;
    }

    private static boolean isRetryable(CapabilityErrorKind kind) {
        return kind == CapabilityErrorKind.TRANSIENT
                || kind == CapabilityErrorKind.TIMEOUT
                || kind == CapabilityErrorKind.RATE_LIMIT;
    }

    private static boolean shouldCacheFailure(CapabilityError error, boolean cacheIdempotencyFailures) {
        if (!cacheIdempotencyFailures) {
            return false;
        }
        if (error == null) {
            return false;
        }
        CapabilityErrorKind kind = error.kind();
        return kind == CapabilityErrorKind.PERMANENT
                || kind == CapabilityErrorKind.CONTRACT
                || kind == CapabilityErrorKind.AUTH
                || kind == CapabilityErrorKind.NOT_FOUND;
    }

    private CapabilityResult fromCachedFailureRecord(FlowStepDefinition step, IdempotencyRecord record) {
        if (record == null || record.success()) {
            return null;
        }
        String raw = record.errorCode();
        if (raw == null || raw.isBlank()) {
            return CapabilityResult.failure(
                    "CAPABILITY_IDEMPOTENCY_CACHED_FAILURE",
                    "Cached idempotency failure blocks capability execution",
                    CapabilityErrorKind.PERMANENT,
                    capabilityDetails(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "idempotencyKey", record.idempotencyKey()
                    )
            );
        }
        String kindPart = raw;
        String codePart = raw;
        int separator = raw.indexOf(':');
        if (separator > 0) {
            kindPart = raw.substring(0, separator);
            codePart = raw.substring(separator + 1);
        }
        CapabilityErrorKind kind = parseKind(kindPart);
        if (kind == CapabilityErrorKind.TRANSIENT
                || kind == CapabilityErrorKind.TIMEOUT
                || kind == CapabilityErrorKind.RATE_LIMIT) {
            return null;
        }
        return CapabilityResult.failure(
                codePart == null || codePart.isBlank() ? "CAPABILITY_IDEMPOTENCY_CACHED_FAILURE" : codePart,
                "Cached idempotency failure blocks capability execution",
                kind,
                capabilityDetails(
                        "capability", step.getCapability(),
                        "operation", step.getOperation(),
                        "idempotencyKey", record.idempotencyKey()
                )
        );
    }

    private Object fromCachedSuccessRecord(IdempotencyRecord record) {
        if (record == null) {
            return null;
        }
        String payload = record.resultJsonRedacted();
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return jsonCodec.fromJsonToObject(payload);
        } catch (RuntimeException exception) {
            return payload;
        }
    }

    private static CapabilityErrorKind parseKind(String value) {
        if (value == null || value.isBlank()) {
            return CapabilityErrorKind.PERMANENT;
        }
        try {
            return CapabilityErrorKind.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return CapabilityErrorKind.PERMANENT;
        }
    }

    private String encodeIdempotencyResult(Object value) {
        String serialized = jsonCodec.toJson(value);
        if (serialized == null) {
            return "null";
        }
        if (serialized.length() > IDEMPOTENCY_RESULT_MAX_CHARS) {
            return serialized.substring(0, IDEMPOTENCY_RESULT_MAX_CHARS);
        }
        return serialized;
    }

    private CapabilityResult circuitOpenFailure(
            FlowStepDefinition step,
            CapabilityOpKey opKey,
            CircuitBreakerState state
    ) {
        return CapabilityResult.failure(
                "CAPABILITY_CIRCUIT_OPEN",
                "Circuit breaker is open for " + step.getCapability() + "." + step.getOperation(),
                CapabilityErrorKind.TRANSIENT,
                capabilityDetails(
                        "tenantId", opKey.tenantId(),
                        "capability", opKey.capabilityName(),
                        "operation", opKey.operationName(),
                        "circuitState", state == null ? CircuitState.OPEN.name() : state.state().name(),
                        "halfOpenAllowedAtMs", state == null ? 0L : state.halfOpenAllowedAtMs()
                )
        );
    }

    private CapabilityResult invokeCapabilityOnce(
            FlowStepDefinition step,
            List<Object> args,
            Map<String, Object> state,
            String correlationId,
            String idempotencyKey,
            long timeoutMs,
            int attempt
    ) {
        List<Object> effectiveArgs = (args == null) ? new ArrayList<>() : args;
        List<Object> callArgs = args;
        Object conceptForDebug = null;

        if (args != null
                && step != null
                && step.getCapability() != null
                && step.getOperation() != null) {

            String capName = step.getCapability().trim().toLowerCase();
            String opName = step.getOperation().trim().toLowerCase();
            boolean isPersistence = "persistence".equals(capName);

            // NPDev convention (debug only for now): infer a concept name for persistence.save
            // from invariant scope or runtime entity name.
            if (isPersistence && "save".equals(opName) && args.size() == 1) {
                Object concept = step.getInvariantScope();
                if (concept == null || String.valueOf(concept).isBlank()) {
                    concept = state == null ? null : state.get("_npdevEntityName");
                }
                if (concept != null && !String.valueOf(concept).isBlank()) {
                    conceptForDebug = concept;
                }

                // Tenant isolation for the FLOW-DRIVEN persistence path: stamp the caller's tenant
                // (carried in flow state, seeded from the ExecutionContext at execute() time) into the
                // entity payload so the capability adapter writes the tenant_id column. The tenant is
                // taken from the execution context, never from caller-supplied data -- mirroring the
                // generated-CRUD path. Reads via capability findById (by globally-unique UUID) are
                // unaffected (IDs are globally-unique UUIDs, not enumerable); query()/list reads are
                // tenant-scoped below, the same way.
                if (args.get(0) instanceof Map<?, ?> entityMap) {
                    Map<String, Object> stamped = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : entityMap.entrySet()) {
                        stamped.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    stamped.put("tenantId", flowStateTenantId(state));
                    // REG-264: tell the adapter this save is an updateConcept, so it refuses an id
                    // with no row instead of creating one (and merges into the row it finds).
                    FlowStepDefinition.ConceptWrite conceptWrite = step.getConceptWrite();
                    if (conceptWrite != null && conceptWrite.isUpdate()) {
                        stamped.put(com.npdev.kernel.ports.PersistenceCapabilityContract.WRITE_MODE_KEY,
                                com.npdev.kernel.ports.PersistenceCapabilityContract.WRITE_MODE_UPDATE);
                    }
                    callArgs = List.of(stamped);
                    effectiveArgs = callArgs;
                }
            }

            // Tenant isolation for flow-driven persistence READS: query()/list() take
            // (concept, criteria) -- stamp the caller's tenant into the criteria map the same way
            // save() stamps it into the entity map, so a flow author gets row-scoped reads for free
            // instead of having to remember to add a tenantId criterion themselves.
            if (isPersistence && ("query".equals(opName) || "list".equals(opName)) && args.size() == 2) {
                Object concept = args.get(0);
                if (concept != null && !String.valueOf(concept).isBlank()) {
                    conceptForDebug = concept;
                }
                Map<String, Object> stamped = new LinkedHashMap<>();
                if (args.get(1) instanceof Map<?, ?> criteriaMap) {
                    for (Map.Entry<?, ?> entry : criteriaMap.entrySet()) {
                        stamped.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                stamped.put("tenantId", flowStateTenantId(state));
                callArgs = List.of(concept, stamped);
                effectiveArgs = callArgs;
            }
        }

        // Verbose capability debug so we can see which adapter is being used at runtime
        LOG.info(String.format(
                "NPDEV-CAPABILITY :: cap=%s op=%s adapterId=%s argsCount=%s concept=%s correlationId=%s attempt=%s",
                step == null ? null : step.getCapability(),
                step == null ? null : step.getOperation(),
                step == null ? null : step.getCapabilityAdapterId(),
                effectiveArgs == null ? 0 : effectiveArgs.size(),
                conceptForDebug,
                correlationId,
                attempt
        ));
CapabilityCall call = new CapabilityCall(
                Objects.requireNonNull(step.getCapability(), "capability is required"),
                step.getCapabilityType(),
                step.getCapabilityAdapterId(),
                Objects.requireNonNull(step.getOperation(), "operation is required"),
                callArgs,
                correlationId,
                idempotencyKey
        );
        Map<String, Object> contextState = new LinkedHashMap<>(state == null ? Map.of() : state);
        contextState.put("capabilityAttempt", attempt);
        if (conceptForDebug != null) {
            contextState.put("_npdevConcept", conceptForDebug);
        }
        Map<String, Object> finalContextState = Map.copyOf(contextState);

        // REG-231: a target that itself doesn't need bounding (see
        // CapabilityDispatcher#requiresBoundedAsyncDispatch) takes the SAME synchronous path as
        // timeoutMs<=0 below -- deliberately reusing it rather than adding a parallel branch, since
        // R2.6's own comment on capabilityInvocationPropagatingCurrentFlow already establishes that
        // running on the caller's thread with no hop at all is the one case that never needs
        // currentFlowContext propagation in the first place (there's no thread boundary to cross).
        if (timeoutMs <= 0 || !capabilityDispatcher.requiresBoundedAsyncDispatch(call)) {
            try {
                return capabilityDispatcher.invoke(call, finalContextState);
            } catch (RuntimeException runtimeException) {
                return CapabilityResult.failure(
                        "CAPABILITY_DISPATCHER_EXCEPTION",
                        runtimeException.getMessage() == null
                                ? "Capability dispatcher threw runtime exception"
                                : runtimeException.getMessage(),
                        classifyCapabilityException(runtimeException),
                        capabilityDetails(
                                "capability", step.getCapability(),
                                "operation", step.getOperation(),
                                "adapterId", step.getCapabilityAdapterId(),
                                "attempt", attempt,
                                "exceptionType", runtimeException.getClass().getName()
                        )
                );
            }
        }

        CompletableFuture<CapabilityResult> future = CompletableFuture.supplyAsync(
                capabilityInvocationPropagatingCurrentFlow(call, finalContextState),
                CAPABILITY_EXECUTOR
        );
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            future.cancel(true);
            return timeoutCapabilityResult(step, attempt, timeoutMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return CapabilityResult.failure(
                    "CAPABILITY_DISPATCHER_INTERRUPTED",
                    "Capability invocation interrupted",
                    CapabilityErrorKind.TRANSIENT,
                    capabilityDetails(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "adapterId", step.getCapabilityAdapterId(),
                            "attempt", attempt
                    )
            );
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            RuntimeException runtime = cause instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(cause == null ? "Capability dispatcher execution failed" : cause.getMessage(), cause);
            return CapabilityResult.failure(
                    "CAPABILITY_DISPATCHER_EXCEPTION",
                    runtime.getMessage() == null
                            ? "Capability dispatcher threw runtime exception"
                            : runtime.getMessage(),
                    classifyCapabilityException(runtime),
                    capabilityDetails(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "adapterId", step.getCapabilityAdapterId(),
                            "attempt", attempt,
                            "exceptionType", runtime.getClass().getName()
                    )
            );
        }
    }

    /**
     * The tenant a flow-driven persistence write is owned by: taken from flow state (seeded from the
     * caller's ExecutionContext at execute() time), falling back to "default" when no tenant claim is
     * present (e.g. an unauthenticated trial boot). Mirrors the generated-CRUD currentTenantId().
     */
    private static String flowStateTenantId(Map<String, Object> state) {
        Object tenant = state == null ? null : state.get("tenantId");
        String tenantId = tenant == null ? null : String.valueOf(tenant).trim();
        return (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
    }

    private static CapabilityResult normalizeCapabilityFailure(
            FlowStepDefinition step,
            CapabilityResult result,
            int attempt
    ) {
        if (result.error() != null) {
            CapabilityError error = result.error();
            Map<String, Object> details = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : error.details().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    details.put(entry.getKey(), entry.getValue());
                }
            }
            details.put("attempt", attempt);
            if (step.getCapability() != null) {
                details.put("capability", step.getCapability());
            }
            if (step.getOperation() != null) {
                details.put("operation", step.getOperation());
            }
            if (step.getCapabilityAdapterId() != null) {
                details.put("adapterId", step.getCapabilityAdapterId());
            }
            return CapabilityResult.failure(new CapabilityError(
                    error.code(),
                    error.message(),
                    error.kind(),
                    details
            ));
        }
        return CapabilityResult.failure(
                "CAPABILITY_RESULT_INVALID",
                "Capability dispatcher returned failed result without error",
                CapabilityErrorKind.PERMANENT,
                capabilityDetails(
                        "attempt", attempt,
                        "capability", step.getCapability(),
                        "operation", step.getOperation(),
                        "adapterId", step.getCapabilityAdapterId()
                )
        );
    }

    private static CapabilityResult timeoutCapabilityResult(
            FlowStepDefinition step,
            int attempt,
            long timeoutMs
    ) {
        return CapabilityResult.failure(
                "CAPABILITY_TIMEOUT",
                "Capability invocation timed out after " + timeoutMs + "ms",
                CapabilityErrorKind.TIMEOUT,
                capabilityDetails(
                        "capability", step.getCapability(),
                        "operation", step.getOperation(),
                        "adapterId", step.getCapabilityAdapterId(),
                        "attempt", attempt,
                        "timeoutMs", timeoutMs
                )
        );
    }

    private static boolean isTimeoutError(CapabilityError error) {
        return error != null && "CAPABILITY_TIMEOUT".equals(error.code());
    }

    private static long remainingTimeout(long timeoutMs, long startedAtEpochMs) {
        if (timeoutMs <= 0) {
            return 0;
        }
        long elapsed = nowEpochMillis() - startedAtEpochMs;
        return timeoutMs - elapsed;
    }

    private static boolean sleepForRetry(long retryDelayMs) {
        if (retryDelayMs <= 0) {
            return true;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Map<String, Object> capabilityDetails(Object... pairs) {
        if (pairs == null || pairs.length == 0) {
            return Map.of();
        }
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("pairs length must be even");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object keyRaw = pairs[i];
            Object value = pairs[i + 1];
            if (keyRaw == null || value == null) {
                continue;
            }
            out.put(String.valueOf(keyRaw), value);
        }
        return Map.copyOf(out);
    }

    /**
     * R2.6 (RUN-4): runs capabilityDispatcher.invoke on a CAPABILITY_EXECUTOR worker while making it
     * observe the calling thread's currentFlowContext, so the timeout branch and the synchronous
     * branch behave identically as far as this kernel's own thread-local state is concerned.
     * Without this, assertCrudInvariantPathAllowed reads null on the worker and stops rejecting the
     * deprecated CRUD invariant path re-entered from inside a capability during flow execution.
     *
     * Only this kernel's own thread-local is propagated. SLF4J's MDC (correlationId, stamped by
     * CorrelationIdFilter) and Spring's RequestContextHolder are the other two thread-bound things
     * a capability can care about, but neither slf4j nor Spring is on the kernel's compile
     * classpath -- adding either as a dependency just to copy a context map is not something this
     * item needs, and the host owns those seams.
     */
    private Supplier<CapabilityResult> capabilityInvocationPropagatingCurrentFlow(
            CapabilityCall call,
            Map<String, Object> contextState
    ) {
        String callerFlowContext = currentFlowContext.get();
        return () -> {
            String workerPrevious = currentFlowContext.get();
            if (callerFlowContext == null) {
                currentFlowContext.remove();
            } else {
                currentFlowContext.set(callerFlowContext);
            }
            try {
                return capabilityDispatcher.invoke(call, contextState);
            } finally {
                if (workerPrevious == null) {
                    currentFlowContext.remove();
                } else {
                    currentFlowContext.set(workerPrevious);
                }
            }
        };
    }
}
