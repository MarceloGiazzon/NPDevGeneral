package com.finalexec.npdev.service;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TimeBoundedPluginExecutionEngine implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TimeBoundedPluginExecutionEngine.class.getName());

    private final long timeoutMs;
    private final ExecutorService executorService;
    private final PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator;
    private final RuntimePluginExecutionSummaryStore executionSummaryStore;

    public TimeBoundedPluginExecutionEngine(
            long timeoutMs,
            PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator,
            RuntimePluginExecutionSummaryStore executionSummaryStore
    ) {
        this.timeoutMs = Math.max(timeoutMs, 1L);
        this.pluginExecutionPolicyEvaluator = Objects.requireNonNull(pluginExecutionPolicyEvaluator, "pluginExecutionPolicyEvaluator");
        this.executionSummaryStore = Objects.requireNonNull(executionSummaryStore, "executionSummaryStore");
        this.executorService = Executors.newCachedThreadPool(new PluginExecutionThreadFactory());
    }

    public SandboxedPluginExecutionResult execute(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            RuntimePluginPackageRealizationService.RealizationSummaryItem realizationSummary,
            CapabilityCall call,
            Map<String, Object> contextState,
            Object handler
    ) {
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(realizationSummary, "realizationSummary");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(handler, "handler");

        PluginExecutionPolicyDecision policyDecision = pluginExecutionPolicyEvaluator.evaluate(contribution, call);
        if (!policyDecision.allowed()) {
            SandboxedPluginExecutionResult executionResult = new SandboxedPluginExecutionResult(
                    SandboxedPluginExecutionResult.Status.DENIED,
                    null,
                    policyDecision.decisionCode(),
                    policyDecision.message(),
                    CapabilityErrorKind.AUTH,
                    contribution.pluginId(),
                    contribution.adapterId(),
                    contribution.capability(),
                    call.operation(),
                    contribution.runtimeRef(),
                    realizationSummary.selectedPackageId(),
                    realizationSummary.selectedPackageVersion(),
                    realizationSummary.selectedPackagePath(),
                    realizationSummary.artifactKind(),
                    realizationSummary.artifactPath(),
                    realizationSummary.artifactRealizationProvider(),
                    realizationSummary.artifactRealizationStrategy(),
                    realizationSummary.realizationStrategy(),
                    Map.of(),
                    call.correlationId(),
                    timeoutMs,
                    0L
            );
            record(executionResult);
            logDenied(executionResult, policyDecision);
            return executionResult;
        }

        long startedAt = System.nanoTime();
        logStart(contribution, realizationSummary, call);
        // REG-4 (2026-07-21): a stray interrupt already pending on the CALLING thread -- e.g. left by
        // an unrelated prior task on the same worker thread under a parallel test/execution run --
        // makes future.get(timeout) throw InterruptedException IMMEDIATELY, before the timeout can
        // fire, turning a genuine timeout into a spurious PLUGIN_EXECUTION_INTERRUPTED (confirmed:
        // executionDurationMs=1 with the caller pre-interrupted). This bounded execution's timeout
        // semantics must not depend on unrelated interrupt state. Clear it for the duration
        // (Thread.interrupted() reads-and-clears) and re-assert it in the finally, so a real pending
        // cancellation is deferred by at most timeoutMs but never swallowed. A NEW interrupt arriving
        // DURING get() still takes the InterruptedException path below, unchanged.
        boolean callerWasInterrupted = Thread.interrupted();
        Future<CapabilityResult> future = executorService.submit(() -> invokeHandler(call, contextState, handler));
        try {
            CapabilityResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            SandboxedPluginExecutionResult executionResult = fromCapabilityResult(
                    contribution,
                    realizationSummary,
                    call,
                    result,
                    elapsedMs(startedAt),
                    SandboxedPluginExecutionResult.Status.SUCCESS
            );
            record(executionResult);
            logFinish(executionResult);
            return executionResult;
        } catch (TimeoutException exception) {
            future.cancel(true);
            SandboxedPluginExecutionResult executionResult = new SandboxedPluginExecutionResult(
                    SandboxedPluginExecutionResult.Status.TIMED_OUT,
                    null,
                    "PLUGIN_EXECUTION_TIMEOUT",
                    "Sandboxed plugin execution timed out after %d ms".formatted(timeoutMs),
                    CapabilityErrorKind.TIMEOUT,
                    contribution.pluginId(),
                    contribution.adapterId(),
                    contribution.capability(),
                    call.operation(),
                    contribution.runtimeRef(),
                    realizationSummary.selectedPackageId(),
                    realizationSummary.selectedPackageVersion(),
                    realizationSummary.selectedPackagePath(),
                    realizationSummary.artifactKind(),
                    realizationSummary.artifactPath(),
                    realizationSummary.artifactRealizationProvider(),
                    realizationSummary.artifactRealizationStrategy(),
                    realizationSummary.realizationStrategy(),
                    Map.of(),
                    call.correlationId(),
                    timeoutMs,
                    elapsedMs(startedAt)
            );
            record(executionResult);
            logFinish(executionResult);
            return executionResult;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            SandboxedPluginExecutionResult executionResult = new SandboxedPluginExecutionResult(
                    SandboxedPluginExecutionResult.Status.FAILED,
                    null,
                    "PLUGIN_EXECUTION_INTERRUPTED",
                    "Sandboxed plugin execution was interrupted",
                    CapabilityErrorKind.TRANSIENT,
                    contribution.pluginId(),
                    contribution.adapterId(),
                    contribution.capability(),
                    call.operation(),
                    contribution.runtimeRef(),
                    realizationSummary.selectedPackageId(),
                    realizationSummary.selectedPackageVersion(),
                    realizationSummary.selectedPackagePath(),
                    realizationSummary.artifactKind(),
                    realizationSummary.artifactPath(),
                    realizationSummary.artifactRealizationProvider(),
                    realizationSummary.artifactRealizationStrategy(),
                    realizationSummary.realizationStrategy(),
                    Map.of(),
                    call.correlationId(),
                    timeoutMs,
                    elapsedMs(startedAt)
            );
            record(executionResult);
            logFinish(executionResult);
            return executionResult;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            SandboxedPluginExecutionResult executionResult = new SandboxedPluginExecutionResult(
                    SandboxedPluginExecutionResult.Status.FAILED,
                    null,
                    "PLUGIN_EXECUTION_EXCEPTION",
                    messageOrDefault(cause, "Sandboxed plugin execution failed"),
                    classifyInvocationError(cause),
                    contribution.pluginId(),
                    contribution.adapterId(),
                    contribution.capability(),
                    call.operation(),
                    contribution.runtimeRef(),
                    realizationSummary.selectedPackageId(),
                    realizationSummary.selectedPackageVersion(),
                    realizationSummary.selectedPackagePath(),
                    realizationSummary.artifactKind(),
                    realizationSummary.artifactPath(),
                    realizationSummary.artifactRealizationProvider(),
                    realizationSummary.artifactRealizationStrategy(),
                    realizationSummary.realizationStrategy(),
                    Map.of(),
                    call.correlationId(),
                    timeoutMs,
                    elapsedMs(startedAt)
            );
            record(executionResult);
            logFinish(executionResult);
            return executionResult;
        } finally {
            // REG-4: re-assert a stray caller interrupt that was cleared above, so genuine
            // cancellation is delivered to the caller after this bounded execution rather than lost.
            if (callerWasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public List<SandboxedPluginExecutionResult.Summary> recentExecutions() {
        return executionSummaryStore.recent(50);
    }

    public Map<String, Object> executionStoreDiagnostics() {
        return executionSummaryStore.diagnostics();
    }

    public Map<String, Object> policySummary() {
        return pluginExecutionPolicyEvaluator.policySummary();
    }

    @Override
    public void close() {
        executorService.shutdownNow();
    }

    private CapabilityResult invokeHandler(CapabilityCall call, Map<String, Object> contextState, Object handler) {
        CapabilityCall effectiveCall = adaptCallForHandler(call, contextState, handler);
        try {
            if (handler instanceof CapabilityAdapter capabilityAdapter) {
                CapabilityResult result = capabilityAdapter.invoke(effectiveCall, contextState);
                if (result == null) {
                    return CapabilityResult.failure(
                            "PLUGIN_EXECUTION_NULL_RESULT",
                            "Sandboxed plugin returned null CapabilityResult",
                            CapabilityErrorKind.PERMANENT,
                            Map.of(
                                    "capability", call.capability(),
                                    "operation", call.operation(),
                                    "adapterId", call.adapterId()
                            )
                    );
                }
                return result;
            }

            if (handler instanceof DynamicCapabilityHandler dynamicCapabilityHandler) {
                CapabilityResult result = dynamicCapabilityHandler.invoke(effectiveCall, contextState);
                if (result == null) {
                    return CapabilityResult.failure(
                            "PLUGIN_EXECUTION_NULL_RESULT",
                            "Sandboxed plugin returned null CapabilityResult",
                            CapabilityErrorKind.PERMANENT,
                            Map.of(
                                    "capability", call.capability(),
                                    "operation", call.operation(),
                                    "adapterId", call.adapterId()
                            )
                    );
                }
                return result;
            }

            Method method = resolveOperation(handler, effectiveCall.operation(), effectiveCall.args());
            method.setAccessible(true);
            if (method.getParameterCount() == 0) {
                return CapabilityResult.success(method.invoke(handler));
            }
            return CapabilityResult.success(method.invoke(handler, effectiveCall.args().toArray()));
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            logInvocationThrowable(call, cause);
            return CapabilityResult.failure(
                    "PLUGIN_EXECUTION_FAILED",
                    messageOrDefault(cause, "Sandboxed plugin execution failed"),
                    classifyInvocationError(cause),
                    Map.of(
                            "capability", call.capability(),
                            "operation", call.operation(),
                            "adapterId", call.adapterId(),
                            "exceptionType", cause.getClass().getName()
                    )
            );
        } catch (ReflectiveOperationException exception) {
            logInvocationThrowable(call, exception);
            return CapabilityResult.failure(
                    "PLUGIN_EXECUTION_DISPATCH_ERROR",
                    "Sandboxed plugin invocation failed for " + call.capability() + "." + call.operation(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of(
                            "capability", call.capability(),
                            "operation", call.operation(),
                            "adapterId", call.adapterId(),
                            "exceptionType", exception.getClass().getName()
                    )
            );
        } catch (RuntimeException exception) {
            logInvocationThrowable(call, exception);
            return CapabilityResult.failure(
                    "PLUGIN_EXECUTION_FAILED",
                    messageOrDefault(exception, "Sandboxed plugin execution failed"),
                    classifyInvocationError(exception),
                    Map.of(
                            "capability", call.capability(),
                            "operation", call.operation(),
                            "adapterId", call.adapterId(),
                            "exceptionType", exception.getClass().getName()
                    )
            );
        }
    }

    private static CapabilityCall adaptCallForHandler(
            CapabilityCall call,
            Map<String, Object> contextState,
            Object handler
    ) {
        if (!"persistence".equalsIgnoreCase(call.capability())
                || !"save".equalsIgnoreCase(call.operation())
                || call.args() == null
                || call.args().size() != 1) {
            return call;
        }

        boolean hasTwoArgSave = false;
        for (Method method : handler.getClass().getMethods()) {
            if ("save".equals(method.getName())
                    && method.getParameterCount() == 2
                    && !method.getDeclaringClass().equals(Object.class)) {
                hasTwoArgSave = true;
                break;
            }
        }

        if (!hasTwoArgSave) {
            return call;
        }

        Object concept = contextState == null ? null : contextState.get("_npdevEntityName");
        if (concept == null || String.valueOf(concept).isBlank()) {
            return call;
        }

        List<Object> enrichedArgs = new ArrayList<>();
        enrichedArgs.add(concept);
        enrichedArgs.addAll(call.args());
        return new CapabilityCall(
                call.capability(),
                call.capabilityType(),
                call.adapterId(),
                call.operation(),
                enrichedArgs,
                call.correlationId(),
                call.idempotencyKey()
        );
    }

    private static Method resolveOperation(Object handler, String operation, List<Object> args) {
        Class<?> type = handler.getClass();
        int argCount = args.size();
        List<Method> candidates = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(operation) || method.getDeclaringClass().equals(Object.class)) {
                continue;
            }
            if (method.getParameterCount() == argCount) {
                candidates.add(method);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Operation not found: " + operation + " on handler " + type.getName());
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // REG-55: more than one method shares this name+argCount -- an overload set differing
        // only in parameter TYPE (e.g. save(Object,Object) vs save(TenantScope,Object)). This
        // used to throw "Ambiguous" unconditionally here, even when the ACTUAL runtime argument
        // values make exactly one candidate legal -- a String concept name can never be a
        // TenantScope, so only save(Object,Object) ever accepts it. Disambiguate by checking
        // which candidates' declared parameter types the actual argument values are assignable
        // to; fall back to the original ambiguous/not-found errors only when that check doesn't
        // narrow to exactly one method.
        List<Method> typeMatched = new ArrayList<>();
        for (Method candidate : candidates) {
            if (acceptsArgumentTypes(candidate, args)) {
                typeMatched.add(candidate);
            }
        }
        if (typeMatched.size() == 1) {
            return typeMatched.get(0);
        }
        if (typeMatched.isEmpty()) {
            throw new IllegalStateException(
                    "Operation not found: %s on handler %s -- %d candidate(s) share this name and %d-argument count, but none accept these actual argument types (%s)"
                            .formatted(operation, type.getName(), candidates.size(), argCount, argumentTypeNames(args))
            );
        }
        throw new IllegalStateException(
                "Ambiguous sandboxed plugin operation %s with %d arguments on handler %s (%d candidates accept these argument types: %s)"
                        .formatted(operation, argCount, type.getName(), typeMatched.size(), argumentTypeNames(args))
        );
    }

    private static boolean acceptsArgumentTypes(Method method, List<Object> args) {
        Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = args.get(i);
            Class<?> paramType = boxed(paramTypes[i]);
            if (arg == null) {
                if (paramTypes[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!paramType.isInstance(arg)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static String argumentTypeNames(List<Object> args) {
        List<String> names = new ArrayList<>();
        for (Object arg : args) {
            names.add(arg == null ? "null" : arg.getClass().getName());
        }
        return String.join(", ", names);
    }

    private SandboxedPluginExecutionResult fromCapabilityResult(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            RuntimePluginPackageRealizationService.RealizationSummaryItem realizationSummary,
            CapabilityCall call,
            CapabilityResult result,
            long durationMs,
            SandboxedPluginExecutionResult.Status successStatus
    ) {
        if (result != null && result.ok()) {
            return new SandboxedPluginExecutionResult(
                    successStatus,
                    result.value(),
                    "",
                    "",
                    null,
                    contribution.pluginId(),
                    contribution.adapterId(),
                    contribution.capability(),
                    call.operation(),
                    contribution.runtimeRef(),
                    realizationSummary.selectedPackageId(),
                    realizationSummary.selectedPackageVersion(),
                    realizationSummary.selectedPackagePath(),
                    realizationSummary.artifactKind(),
                    realizationSummary.artifactPath(),
                    realizationSummary.artifactRealizationProvider(),
                    realizationSummary.artifactRealizationStrategy(),
                    realizationSummary.realizationStrategy(),
                    SandboxedPluginExecutionResult.summarizeOutputEvidence(result.value()),
                    call.correlationId(),
                    timeoutMs,
                    durationMs
            );
        }

        return new SandboxedPluginExecutionResult(
                SandboxedPluginExecutionResult.Status.FAILED,
                null,
                result == null || result.error() == null ? "PLUGIN_EXECUTION_FAILED" : result.error().code(),
                result == null || result.error() == null
                        ? "Sandboxed plugin execution failed"
                        : result.error().message(),
                result == null || result.error() == null ? CapabilityErrorKind.PERMANENT : result.error().kind(),
                contribution.pluginId(),
                contribution.adapterId(),
                contribution.capability(),
                call.operation(),
                contribution.runtimeRef(),
                realizationSummary.selectedPackageId(),
                realizationSummary.selectedPackageVersion(),
                realizationSummary.selectedPackagePath(),
                realizationSummary.artifactKind(),
                realizationSummary.artifactPath(),
                realizationSummary.artifactRealizationProvider(),
                realizationSummary.artifactRealizationStrategy(),
                realizationSummary.realizationStrategy(),
                Map.of(),
                call.correlationId(),
                timeoutMs,
                durationMs
        );
    }

    private void record(SandboxedPluginExecutionResult executionResult) {
        executionSummaryStore.append(executionResult.toSummary());
    }

    private static long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static CapabilityErrorKind classifyInvocationError(Throwable throwable) {
        // Unwrap reflective/async wrappers (an adapter invoked via reflection inside a Future arrives
        // as InvocationTargetException/ExecutionException wrapping the real cause). Without this, a
        // contract failure thrown by an adapter — e.g. IllegalArgumentException for a FK/unique
        // integrity violation — would be read as the generic wrapper and fall through to PERMANENT
        // (system_exception) instead of CONTRACT (capability_contract).
        Throwable root = throwable;
        int guard = 0;
        while (root != null
                && (root instanceof java.lang.reflect.InvocationTargetException
                        || root instanceof java.util.concurrent.ExecutionException
                        || root instanceof java.util.concurrent.CompletionException)
                && root.getCause() != null
                && root.getCause() != root
                && guard++ < 16) {
            root = root.getCause();
        }
        if (root == null) {
            root = throwable;
        }

        if (root instanceof IllegalArgumentException
                || root instanceof ClassCastException
                || root instanceof UnsupportedOperationException) {
            return CapabilityErrorKind.CONTRACT;
        }

        String typeName = root.getClass().getName().toLowerCase();
        String message = root.getMessage() == null ? "" : root.getMessage().toLowerCase();
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
        if (typeName.contains("timeout") || message.contains("timeout")) {
            return CapabilityErrorKind.TIMEOUT;
        }
        if (typeName.contains("transient")
                || typeName.contains("temporar")
                || throwable instanceof InterruptedException) {
            return CapabilityErrorKind.TRANSIENT;
        }
        return CapabilityErrorKind.PERMANENT;
    }

    private void logInvocationThrowable(CapabilityCall call, Throwable throwable) {
        if (throwable == null) {
            return;
        }
        LOG.log(
                Level.SEVERE,
                "NPDEV-PLUGIN-SANDBOX :: phase=exception capability=%s operation=%s adapterId=%s correlationId=%s exceptionType=%s message=%s"
                        .formatted(
                                call.capability(),
                                call.operation(),
                                call.adapterId(),
                                call.correlationId(),
                                throwable.getClass().getName(),
                                messageOrDefault(throwable, "Sandboxed plugin execution failed")
                        ),
                throwable
        );
    }

    private void logStart(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            RuntimePluginPackageRealizationService.RealizationSummaryItem realizationSummary,
            CapabilityCall call
    ) {
        LOG.info(
                "NPDEV-PLUGIN-SANDBOX :: phase=start pluginId=%s adapterId=%s capability=%s operation=%s runtimeRef=%s selectedPackageId=%s artifactKind=%s strategy=%s correlationId=%s timeoutMs=%d"
                        .formatted(
                                contribution.pluginId(),
                                contribution.adapterId(),
                                contribution.capability(),
                                call.operation(),
                                contribution.runtimeRef(),
                                realizationSummary.selectedPackageId(),
                                realizationSummary.artifactKind(),
                                realizationSummary.realizationStrategy(),
                                call.correlationId(),
                                timeoutMs
                        )
        );
    }

    private void logFinish(SandboxedPluginExecutionResult result) {
        LOG.info(
                "NPDEV-PLUGIN-SANDBOX :: phase=finish status=%s pluginId=%s adapterId=%s capability=%s operation=%s runtimeRef=%s selectedPackageId=%s artifactKind=%s strategy=%s correlationId=%s durationMs=%d timeoutMs=%d errorCode=%s"
                        .formatted(
                                result.status(),
                                result.pluginId(),
                                result.adapterId(),
                                result.capability(),
                                result.operation(),
                                result.runtimeRef(),
                                result.selectedPackageId(),
                                result.artifactKind(),
                                result.realizationStrategy(),
                                result.correlationId(),
                                result.executionDurationMs(),
                                result.timeoutMs(),
                                result.errorCode()
                        )
        );
    }

    private void logDenied(SandboxedPluginExecutionResult result, PluginExecutionPolicyDecision policyDecision) {
        LOG.info(
                "NPDEV-PLUGIN-SANDBOX :: phase=denied status=%s pluginId=%s adapterId=%s capability=%s operation=%s runtimeEnvironment=%s denyCode=%s message=%s"
                        .formatted(
                                result.status(),
                                result.pluginId(),
                                result.adapterId(),
                                result.capability(),
                                result.operation(),
                                policyDecision.runtimeEnvironment(),
                                policyDecision.decisionCode(),
                                policyDecision.message()
                        )
        );
    }

    private static String messageOrDefault(Throwable throwable, String fallback) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return fallback;
        }
        return throwable.getMessage();
    }

    private static final class PluginExecutionThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "npdev-plugin-sandbox-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
