package com.finalexec.npdev.service.pluginipc;

import java.util.List;
import java.util.Map;

/**
 * The three frame shapes exchanged over a plugin IPC channel (SEC-3 / Model B,
 * docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 1). Every frame carries a
 * {@code requestId}: for {@link InvokeFrame} and the terminal {@link ResponseFrame} that answers it,
 * this is the invocation's own id; for {@link CallbackFrame} it is that same invocation's id (so the
 * host can tell which in-flight invoke a callback belongs to), while {@link CallbackFrame#callbackId()}
 * is the id the matching {@link ResponseFrame} to THAT callback echoes back.
 */
public sealed interface PluginIpcFrame
        permits PluginIpcFrame.InvokeFrame, PluginIpcFrame.CallbackFrame, PluginIpcFrame.ResponseFrame {

    String FRAME_KIND_INVOKE = "invoke";
    String FRAME_KIND_CALLBACK = "callback";
    String FRAME_KIND_RESPONSE = "response";

    String requestId();

    /**
     * host -> child: run this plugin with this {@code CapabilityCall}. Once per invocation.
     *
     * <p>{@code handlerClassName} is {@code null} for a one-shot child (SEC-3 step 2: the process was
     * spawned already bound to one handler class via its command-line arg, so the frame does not need to
     * repeat it) and non-null for a pooled, fungible worker (SEC-3 step 3, design section 2: a worker is
     * not bound to any one plugin, so which class to instantiate travels in the invoke frame itself).</p>
     */
    record InvokeFrame(
            String requestId,
            String capability,
            String capabilityType,
            String adapterId,
            String operation,
            List<Object> args,
            String correlationId,
            String idempotencyKey,
            Map<String, Object> contextState,
            String handlerClassName
    ) implements PluginIpcFrame {
    }

    /** child -> host: mid-flight capability call the plugin's own handler code needs. Zero or more per invoke. */
    record CallbackFrame(
            String requestId,
            String callbackId,
            String capability,
            String operation,
            List<Object> args
    ) implements PluginIpcFrame {
    }

    /** Either direction: answers a prior invoke ({@code requestId}) or callback ({@code callbackId}) by id. */
    record ResponseFrame(
            String requestId,
            boolean ok,
            Object value,
            ResponseError error
    ) implements PluginIpcFrame {

        public static ResponseFrame success(String requestId, Object value) {
            return new ResponseFrame(requestId, true, value, null);
        }

        public static ResponseFrame failure(
                String requestId,
                String errorCode,
                String errorMessage,
                String errorKind,
                Map<String, Object> errorDetails
        ) {
            return new ResponseFrame(requestId, false, null, new ResponseError(errorCode, errorMessage, errorKind, errorDetails));
        }
    }

    /** Mirrors {@code com.npdev.kernel.CapabilityError}'s shape (code/message/kind/details). */
    record ResponseError(String code, String message, String kind, Map<String, Object> details) {
    }
}
