package com.npdev.adapters.flowcompiled;

public final class UnknownFlowException extends RuntimeException {
    public UnknownFlowException(String flowName) {
        super("Unknown flow: " + flowName);
    }
}
