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
    private final String uid;

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
        this(name, type, specializesName, operations, origin, null);
    }

    /** P2.1: a stable identity for this capability, generated once and never reused -- see getUid. */
    public CapabilityAst(
            String name,
            String type,
            String specializesName,
            List<CapabilityOperationAst> operations,
            OriginAst origin,
            String uid
    ) {
        this.name = name;
        this.type = type;
        this.specializesName = specializesName;
        this.operations = new ArrayList<>(operations);
        this.origin = origin;
        this.uid = uid;
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

    /** P2.1: a stable identity for this capability, generated once and never reused
     *  (npdev migrate assign-uids stamps one); null if the capability declares none. */
    public String getUid() {
        return uid;
    }

    public List<CapabilityOperationAst> getOperations() {
        return Collections.unmodifiableList(operations);
    }
}
