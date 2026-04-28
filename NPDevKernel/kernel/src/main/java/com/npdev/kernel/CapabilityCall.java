package com.npdev.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record CapabilityCall(
        String capability,
        String capabilityType,
        String adapterId,
        String operation,
        List<Object> args,
        String correlationId,
        String idempotencyKey
) {
    public CapabilityCall(String capability, String operation, Object input) {
        this(capability, null, null, operation, input == null ? List.of() : List.of(input), null, null);
    }

    public CapabilityCall(String capability, String capabilityType, String operation, Object input) {
        this(capability, capabilityType, null, operation, input == null ? List.of() : List.of(input), null, null);
    }

    public CapabilityCall(
            String capability,
            String capabilityType,
            String adapterId,
            String operation,
            Object input
    ) {
        this(capability, capabilityType, adapterId, operation, input == null ? List.of() : List.of(input), null, null);
    }

    public CapabilityCall(
            String capability,
            String capabilityType,
            String adapterId,
            String operation,
            List<Object> args
    ) {
        this(capability, capabilityType, adapterId, operation, args, null, null);
    }

    public CapabilityCall(
            String capability,
            String capabilityType,
            String adapterId,
            String operation,
            List<Object> args,
            String correlationId
    ) {
        this(capability, capabilityType, adapterId, operation, args, correlationId, null);
    }

    public CapabilityCall {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability must be non-blank");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must be non-blank");
        }
        args = args == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(args));
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must be null or non-blank");
        }
    }

    public Object input() {
        if (args.isEmpty()) {
            return null;
        }
        return args.get(0);
    }
}
