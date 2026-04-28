package com.npdev.dsl.v1.compiled;

public final class CompiledCapabilityBinding {
    private final String capability;
    private final String adapter;

    public CompiledCapabilityBinding(String capability, String adapter) {
        this.capability = capability;
        this.adapter = adapter;
    }

    public String getCapability() { return capability; }

    public String getAdapter() { return adapter; }
}
