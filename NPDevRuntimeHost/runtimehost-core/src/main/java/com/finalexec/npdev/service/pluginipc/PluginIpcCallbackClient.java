package com.finalexec.npdev.service.pluginipc;

import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Child-side stand-in for "call back into the host." A plugin handler running inside
 * {@link PluginIpcChildRuntime} gets one of these instead of a direct in-process reference to a
 * capability like {@code AuditLogStore} -- calling it sends a {@code callback} frame to the host over
 * the channel's outbound pipe and blocks for the matching {@code response} frame on the inbound pipe.
 * See docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 1.
 */
public final class PluginIpcCallbackClient {

    private final String requestId;
    private final InputStream in;
    private final OutputStream out;

    public PluginIpcCallbackClient(String requestId, InputStream in, OutputStream out) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
    }

    public CapabilityResult callBack(String capability, String operation, List<Object> args) {
        PluginIpcJsonSafeValues.requireJsonSafeArgs(capability + "." + operation + ".args", args);
        String callbackId = UUID.randomUUID().toString();
        try {
            synchronized (out) {
                PluginIpcFrameCodec.writeCallback(out, new PluginIpcFrame.CallbackFrame(
                        requestId, callbackId, capability, operation, args
                ));
            }
            while (true) {
                PluginIpcFrame frame = PluginIpcFrameCodec.readFrame(in);
                if (frame == null) {
                    return CapabilityResult.failure(
                            "PLUGIN_IPC_CHANNEL_CLOSED",
                            "Plugin IPC channel closed while awaiting callback response",
                            CapabilityErrorKind.PERMANENT,
                            Map.of("capability", capability, "operation", operation)
                    );
                }
                if (frame instanceof PluginIpcFrame.ResponseFrame response
                        && callbackId.equals(response.requestId())) {
                    return toCapabilityResult(response);
                }
                // A stray/late frame not answering this callback (e.g. a prior callback's response
                // arriving out of order) -- ignore and keep waiting for this one.
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static CapabilityResult toCapabilityResult(PluginIpcFrame.ResponseFrame response) {
        if (response.ok()) {
            return CapabilityResult.success(response.value());
        }
        PluginIpcFrame.ResponseError error = response.error();
        return CapabilityResult.failure(
                error == null ? "PLUGIN_IPC_MISSING_ERROR" : error.code(),
                error == null ? "Plugin IPC response marked failed but carried no error" : error.message(),
                parseErrorKind(error == null ? null : error.kind()),
                error == null || error.details() == null ? Map.of() : error.details()
        );
    }

    private static CapabilityErrorKind parseErrorKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return CapabilityErrorKind.PERMANENT;
        }
        try {
            return CapabilityErrorKind.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            return CapabilityErrorKind.PERMANENT;
        }
    }
}
