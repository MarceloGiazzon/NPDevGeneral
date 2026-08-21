package com.finalexec.boundary;

import java.time.Instant;

/**
 * Wave 2 / B2-B10: unified boundary-violation envelope. Every boundary enforcement point --
 * whether it fires at boot, over REST, or at runtime-auth -- produces one of these so the
 * caller sees a consistent shape: which boundary, which surface, what happened, when.
 */
public record BoundaryViolation(
    String boundaryId,
    String surface,
    String message,
    Instant timestamp
) {
    public String toJson() {
        return String.format(
            "{\"boundaryId\":\"%s\",\"surface\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
            boundaryId, surface, message.replace("\"", "\\\""), timestamp);
    }
}
