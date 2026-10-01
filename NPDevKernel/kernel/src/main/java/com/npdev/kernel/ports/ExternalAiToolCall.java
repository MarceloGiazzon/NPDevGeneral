package com.npdev.kernel.ports;

import java.util.Map;

/** AGENT-1: one tool-call the vendor asked for. {@code id} is vendor-supplied, or synthesized
 *  (e.g. {@code "gemini-0"}) when the vendor omits one (Gemini). */
public record ExternalAiToolCall(String id, String name, Map<String, Object> arguments) {
}
