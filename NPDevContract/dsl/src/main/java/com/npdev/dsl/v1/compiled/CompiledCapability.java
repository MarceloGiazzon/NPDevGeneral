package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledCapability {
    private final String name;
    private final String type;
    private final List<CompiledCapabilityOperation> operations;

    public CompiledCapability(String name, List<CompiledCapabilityOperation> operations) {
        this(name, null, operations);
    }

    public CompiledCapability(String name, String type, List<CompiledCapabilityOperation> operations) {
        this.name = name;
        this.type = type;
        this.operations = new ArrayList<>(operations);
    }

    public String getName() { return name; }

    public String getType() { return type; }

    public List<CompiledCapabilityOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }
}
