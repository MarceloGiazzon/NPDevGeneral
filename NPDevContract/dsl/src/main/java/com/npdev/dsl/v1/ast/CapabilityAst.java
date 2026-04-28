package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CapabilityAst {
    private final String name;
    private final String type;
    private final String specializesName;
    private final List<CapabilityOperationAst> operations;

    public CapabilityAst(String name, List<CapabilityOperationAst> operations) {
        this(name, null, null, operations);
    }

    public CapabilityAst(String name, String type, List<CapabilityOperationAst> operations) {
        this(name, type, null, operations);
    }

    public CapabilityAst(
            String name,
            String type,
            String specializesName,
            List<CapabilityOperationAst> operations
    ) {
        this.name = name;
        this.type = type;
        this.specializesName = specializesName;
        this.operations = new ArrayList<>(operations);
    }

    public String getName() { return name; }

    public String getType() { return type; }

    public String getSpecializesName() {
        return specializesName;
    }

    public List<CapabilityOperationAst> getOperations() {
        return Collections.unmodifiableList(operations);
    }
}
