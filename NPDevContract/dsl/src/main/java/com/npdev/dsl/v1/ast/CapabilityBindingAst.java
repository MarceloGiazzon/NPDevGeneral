package com.npdev.dsl.v1.ast;

public final class CapabilityBindingAst {
    private final String capability;
    private final String adapter;

    public CapabilityBindingAst(String capability, String adapter) {
        this.capability = capability;
        this.adapter = adapter;
    }

    public String getCapability() { return capability; }

    public String getAdapter() { return adapter; }
}
