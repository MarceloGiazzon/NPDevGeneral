package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CapabilityAst {
    private final String name;
    private final String type;
    private final String specializesName;
    private final List<CapabilityOperationAst> operations;
    private final OriginAst origin;

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
        this(name, type, specializesName, operations, null);
    }

    /** PACK-2: attaches pack-attribution provenance (see getOrigin) -- null for an app's own root-
     *  or context-declared capability, non-null for a pack-contributed one. */
    public CapabilityAst(
            String name,
            String type,
            String specializesName,
            List<CapabilityOperationAst> operations,
            OriginAst origin
    ) {
        this.name = name;
        this.type = type;
        this.specializesName = specializesName;
        this.operations = new ArrayList<>(operations);
        this.origin = origin;
    }

    public String getName() { return name; }

    public String getType() { return type; }

    public String getSpecializesName() {
        return specializesName;
    }

    /** PACK-2: pack-attribution provenance, or null if this capability is not pack-contributed. */
    public OriginAst getOrigin() {
        return origin;
    }

    public List<CapabilityOperationAst> getOperations() {
        return Collections.unmodifiableList(operations);
    }
}
