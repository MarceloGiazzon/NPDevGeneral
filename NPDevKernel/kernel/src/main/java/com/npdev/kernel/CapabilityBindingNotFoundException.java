package com.npdev.kernel;

public final class CapabilityBindingNotFoundException extends RuntimeException {
    public CapabilityBindingNotFoundException(String capability, String adapterId) {
        super("Capability binding not found for capability '" + capability + "' and adapter '" + adapterId + "'");
    }
}
