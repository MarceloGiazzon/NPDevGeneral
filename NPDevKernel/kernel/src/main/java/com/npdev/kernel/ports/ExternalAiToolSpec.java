package com.npdev.kernel.ports;

import java.util.Map;

/** AGENT-1: one tool offered to an external-AI vendor in a {@link ExternalAiToolChatRequest}.
 *  {@code parameters} is a JSON-Schema object (type/properties/required/description/enum/items only
 *  -- see the adapter for which keywords each vendor's function-declaration schema rejects). */
public record ExternalAiToolSpec(String name, String description, Map<String, Object> parameters) {
}
