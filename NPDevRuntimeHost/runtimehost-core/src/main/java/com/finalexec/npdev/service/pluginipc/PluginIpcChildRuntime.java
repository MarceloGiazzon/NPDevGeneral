package com.finalexec.npdev.service.pluginipc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Child-side stand-in for the eventual separate OS process: reads {@code invoke} frame(s), runs the
 * plugin handler against a {@link PluginIpcCallbackClient} in place of its normal in-process capability
 * references, and writes a terminal {@code response} frame for each. Step-1 prototype scope per
 * docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 6: same JVM, no process spawning yet --
 * only {@link CapabilityAdapter}-shaped handlers are supported here (the shape the shipped auditLog
 * plugin uses), not the {@code DynamicCapabilityHandler}/reflective dispatch paths
 * {@code TimeBoundedPluginExecutionEngine} also supports in-process; those are unrelated to the IPC
 * protocol question this prototype proves out.
 */
public final class PluginIpcChildRuntime {

    private final InputStream in;
    private final OutputStream out;

    public PluginIpcChildRuntime(InputStream in, OutputStream out) {
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Blocks reading one invoke frame, runs the handler {@code handlerFactory} builds against a callback
     * client bound to this request, and writes the terminal response frame. Returns once that response
     * has been sent. SEC-3 step 2's one-shot-process-per-invoke shape: the handler class is already known
     * to {@code handlerFactory} (bound at process-spawn time), so it does not need the invoke frame itself.
     */
    public void runOnce(Function<PluginIpcCallbackClient, CapabilityAdapter> handlerFactory) {
        try {
            PluginIpcFrame frame = PluginIpcFrameCodec.readFrame(in);
            if (!(frame instanceof PluginIpcFrame.InvokeFrame invoke)) {
                throw new IllegalStateException("Expected an invoke frame, got: " + frame);
            }
            processOneInvoke(invoke, handlerFactory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Loops reading invoke frames and answering each with a terminal response, until the host closes its
     * side of the channel (a clean EOF -- {@link PluginIpcFrameCodec#readFrame} returns {@code null}),
     * which this treats as the graceful-shutdown signal design section 2 describes for a pooled worker
     * ("sends a close frame to every live worker"): rather than adding a fourth wire frame kind for it,
     * closing the pipe itself is the signal, since a worker idle between invocations is already blocked in
     * exactly the read this responds to. SEC-3 step 3's fungible-worker shape: {@code handlerFactory} gets
     * the CURRENT invoke frame (so it can read {@link PluginIpcFrame.InvokeFrame#handlerClassName()}) since
     * a pooled worker is not bound to any one plugin class the way a step-2 one-shot process is.
     */
    public void runUntilClosed(BiFunction<PluginIpcFrame.InvokeFrame, PluginIpcCallbackClient, CapabilityAdapter> handlerFactory) {
        try {
            while (true) {
                PluginIpcFrame frame = PluginIpcFrameCodec.readFrame(in);
                if (frame == null) {
                    return;
                }
                if (!(frame instanceof PluginIpcFrame.InvokeFrame invoke)) {
                    throw new IllegalStateException("Expected an invoke frame, got: " + frame);
                }
                processOneInvoke(invoke, callbackClient -> handlerFactory.apply(invoke, callbackClient));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void processOneInvoke(
            PluginIpcFrame.InvokeFrame invoke, Function<PluginIpcCallbackClient, CapabilityAdapter> handlerFactory
    ) throws IOException {
        PluginIpcCallbackClient callbackClient = new PluginIpcCallbackClient(invoke.requestId(), in, out);
        CapabilityAdapter handler = handlerFactory.apply(callbackClient);
        CapabilityCall call = new CapabilityCall(
                invoke.capability(),
                invoke.capabilityType(),
                invoke.adapterId(),
                invoke.operation(),
                invoke.args(),
                invoke.correlationId(),
                invoke.idempotencyKey()
        );
        Map<String, Object> contextState = invoke.contextState() == null ? Map.of() : invoke.contextState();
        CapabilityResult result = invokeHandlerSafely(handler, call, contextState);
        PluginIpcJsonSafeValues.requireJsonSafe("response.value", result.value());
        PluginIpcFrameCodec.writeResponse(out, toResponseFrame(invoke.requestId(), result));
    }

    private static CapabilityResult invokeHandlerSafely(
            CapabilityAdapter handler, CapabilityCall call, Map<String, Object> contextState
    ) {
        CapabilityResult result;
        try {
            result = handler.invoke(call, contextState);
        } catch (RuntimeException exception) {
            return CapabilityResult.failure(
                    "PLUGIN_EXECUTION_FAILED",
                    exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage(),
                    CapabilityErrorKind.PERMANENT,
                    Map.of(
                            "capability", call.capability(),
                            "operation", call.operation(),
                            "exceptionType", exception.getClass().getName()
                    )
            );
        }
        if (result == null) {
            return CapabilityResult.failure(
                    "PLUGIN_EXECUTION_NULL_RESULT",
                    "Plugin IPC child handler returned null CapabilityResult",
                    CapabilityErrorKind.PERMANENT,
                    Map.of("capability", call.capability(), "operation", call.operation())
            );
        }
        return result;
    }

    private static PluginIpcFrame.ResponseFrame toResponseFrame(String requestId, CapabilityResult result) {
        if (result.ok()) {
            return PluginIpcFrame.ResponseFrame.success(requestId, result.value());
        }
        return PluginIpcFrame.ResponseFrame.failure(
                requestId,
                result.error().code(),
                result.error().message(),
                result.error().kind().name(),
                result.error().details()
        );
    }
}
