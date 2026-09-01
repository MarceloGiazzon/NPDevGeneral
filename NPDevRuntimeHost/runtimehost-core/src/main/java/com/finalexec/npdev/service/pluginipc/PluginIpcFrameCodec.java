package com.finalexec.npdev.service.pluginipc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-prefixed JSON frame wire format shared by the plugin IPC host and child sides:
 * {@code [4-byte big-endian length][UTF-8 JSON payload]}, one {@link PluginIpcFrame} per frame.
 * See docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 1. Reuses Jackson (already a hard
 * dependency of this module) -- no new library.
 */
public final class PluginIpcFrameCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private PluginIpcFrameCodec() {
    }

    public static void writeInvoke(OutputStream out, PluginIpcFrame.InvokeFrame frame) throws IOException {
        writeFrame(out, PluginIpcFrame.FRAME_KIND_INVOKE, frame);
    }

    public static void writeCallback(OutputStream out, PluginIpcFrame.CallbackFrame frame) throws IOException {
        writeFrame(out, PluginIpcFrame.FRAME_KIND_CALLBACK, frame);
    }

    public static void writeResponse(OutputStream out, PluginIpcFrame.ResponseFrame frame) throws IOException {
        writeFrame(out, PluginIpcFrame.FRAME_KIND_RESPONSE, frame);
    }

    private static void writeFrame(OutputStream out, String kind, Object frame) throws IOException {
        ObjectNode node = MAPPER.valueToTree(frame);
        node.put("kind", kind);
        byte[] payload = MAPPER.writeValueAsBytes(node);
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    /**
     * Reads and decodes one frame into whichever concrete {@link PluginIpcFrame} record its {@code kind}
     * names. Returns {@code null} at a clean end-of-stream (the peer closed its side of the channel with
     * no frame in flight) rather than throwing, so callers can use it as a natural loop terminator.
     */
    public static PluginIpcFrame readFrame(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int length;
        try {
            length = data.readInt();
        } catch (EOFException eof) {
            return null;
        }
        if (length < 0) {
            throw new IOException("Negative plugin IPC frame length: " + length);
        }
        byte[] payload = data.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException(
                    "Plugin IPC frame truncated: expected " + length + " bytes, got " + payload.length
            );
        }
        JsonNode node = MAPPER.readTree(payload);
        String kind = node.path("kind").asText("");
        return switch (kind) {
            case PluginIpcFrame.FRAME_KIND_INVOKE -> MAPPER.treeToValue(node, PluginIpcFrame.InvokeFrame.class);
            case PluginIpcFrame.FRAME_KIND_CALLBACK -> MAPPER.treeToValue(node, PluginIpcFrame.CallbackFrame.class);
            case PluginIpcFrame.FRAME_KIND_RESPONSE -> MAPPER.treeToValue(node, PluginIpcFrame.ResponseFrame.class);
            default -> throw new IOException("Unknown plugin IPC frame kind: '" + kind + "'");
        };
    }
}
