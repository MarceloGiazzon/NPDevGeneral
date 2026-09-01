package com.finalexec.npdev.service.pluginipc;

import com.finalexec.npdev.service.PluginExecutionPolicyDecision;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Host-side orchestrator for one plugin invocation over the {@link PluginIpcFrameCodec} wire format:
 * sends the {@code invoke} frame, then answers every {@code callback} frame the child sends back --
 * enforcing the callback allowlist ({@link PluginExecutionPolicyEvaluator#evaluateCallback}) against
 * every one before dispatching it to a real host-side capability -- until the child's terminal
 * {@code response} frame arrives. Step-1 prototype scope: this class talks over whatever streams it is
 * given (piped streams standing in for a process today; a real child process's stdin/stdout once step 2
 * lands) -- it has no opinion on how those streams were created. See
 * docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md sections 1 and 6.
 */
public final class PluginIpcHostSession {

    private static final Logger LOG = Logger.getLogger(PluginIpcHostSession.class.getName());

    private final RuntimePluginAdapterRegistry registry;
    private final PluginExecutionPolicyEvaluator policyEvaluator;
    private final PluginIpcCallbackDispatcher callbackDispatcher;

    public PluginIpcHostSession(
            RuntimePluginAdapterRegistry registry,
            PluginExecutionPolicyEvaluator policyEvaluator,
            PluginIpcCallbackDispatcher callbackDispatcher
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policyEvaluator = Objects.requireNonNull(policyEvaluator, "policyEvaluator");
        this.callbackDispatcher = Objects.requireNonNull(callbackDispatcher, "callbackDispatcher");
    }

    /**
     * Sends {@code call} to the child as an invoke frame over {@code childIn} (the child's inbound pipe)
     * and blocks, answering callback frames read from {@code childOut} (the child's outbound pipe), until
     * the child's terminal response frame for this invocation arrives. Equivalent to {@link
     * #invoke(RuntimePluginAdapterRegistry.RegisteredAdapterContribution, CapabilityCall, Map, String,
     * InputStream, OutputStream)} with a {@code null} handler class name -- the shape a one-shot child
     * (SEC-3 step 2) expects, since its handler class was already bound at process-spawn time.
     */
    public CapabilityResult invoke(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            CapabilityCall call,
            Map<String, Object> contextState,
            InputStream childOut,
            OutputStream childIn
    ) {
        return invoke(contribution, call, contextState, null, childOut, childIn);
    }

    /**
     * Same as {@link #invoke(RuntimePluginAdapterRegistry.RegisteredAdapterContribution, CapabilityCall,
     * Map, InputStream, OutputStream)}, additionally naming {@code handlerClassName} in the invoke frame
     * -- required for a pooled, fungible worker (SEC-3 step 3) that is not bound to any one plugin class.
     */
    public CapabilityResult invoke(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            CapabilityCall call,
            Map<String, Object> contextState,
            String handlerClassName,
            InputStream childOut,
            OutputStream childIn
    ) {
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(childOut, "childOut");
        Objects.requireNonNull(childIn, "childIn");

        PluginIpcJsonSafeValues.requireJsonSafeArgs("invoke.args", call.args());
        String requestId = UUID.randomUUID().toString();
        try {
            synchronized (childIn) {
                PluginIpcFrameCodec.writeInvoke(childIn, new PluginIpcFrame.InvokeFrame(
                        requestId,
                        call.capability(),
                        call.capabilityType(),
                        call.adapterId(),
                        call.operation(),
                        call.args(),
                        call.correlationId(),
                        call.idempotencyKey(),
                        contextState == null ? Map.of() : contextState,
                        handlerClassName
                ));
            }
            while (true) {
                PluginIpcFrame frame = PluginIpcFrameCodec.readFrame(childOut);
                if (frame == null) {
                    return CapabilityResult.failure(
                            "PLUGIN_IPC_CHANNEL_CLOSED",
                            "Plugin IPC channel closed before a response was received",
                            CapabilityErrorKind.PERMANENT,
                            Map.of("capability", call.capability(), "operation", call.operation())
                    );
                }
                if (frame instanceof PluginIpcFrame.CallbackFrame callback
                        && requestId.equals(callback.requestId())) {
                    handleCallback(contribution, callback, childIn);
                    continue;
                }
                if (frame instanceof PluginIpcFrame.ResponseFrame response
                        && requestId.equals(response.requestId())) {
                    return PluginIpcCallbackClient.toCapabilityResult(response);
                }
                LOG.log(Level.WARNING, "Ignoring stray plugin IPC frame for a different requestId: {0}", frame);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void handleCallback(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            PluginIpcFrame.CallbackFrame callback,
            OutputStream childIn
    ) throws IOException {
        PluginExecutionPolicyDecision decision = policyEvaluator.evaluateCallback(
                contribution, registry, callback.capability(), callback.operation()
        );
        PluginIpcFrame.ResponseFrame response;
        if (!decision.allowed()) {
            LOG.log(Level.WARNING, "Denied plugin IPC callback: {0}", decision.toSummary());
            response = PluginIpcFrame.ResponseFrame.failure(
                    callback.callbackId(),
                    decision.decisionCode(),
                    decision.message(),
                    CapabilityErrorKind.AUTH.name(),
                    Map.of("capability", callback.capability(), "operation", callback.operation())
            );
        } else {
            CapabilityCall callbackCall = new CapabilityCall(
                    callback.capability(), null, null, callback.operation(), callback.args()
            );
            CapabilityResult result = callbackDispatcher.dispatch(callbackCall);
            response = result.ok()
                    ? PluginIpcFrame.ResponseFrame.success(callback.callbackId(), result.value())
                    : PluginIpcFrame.ResponseFrame.failure(
                            callback.callbackId(),
                            result.error().code(),
                            result.error().message(),
                            result.error().kind().name(),
                            result.error().details()
                    );
        }
        synchronized (childIn) {
            PluginIpcFrameCodec.writeResponse(childIn, response);
        }
    }

    /** How the host actually performs an allowed callback against a real capability. */
    @FunctionalInterface
    public interface PluginIpcCallbackDispatcher {
        CapabilityResult dispatch(CapabilityCall call);
    }
}
