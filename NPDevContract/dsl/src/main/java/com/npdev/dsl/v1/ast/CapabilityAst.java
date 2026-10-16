package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CapabilityAst {
    private final String name;
    private final String type;
    private final String specializesName;
    private final List<CapabilityOperationAst> operations;
    private final OriginAst origin;
    private final String uid;
    private final Map<String, Object> http;

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
        this(name, type, specializesName, operations, origin, uid, Map.of());
    }

    /** INT-1: declarative HTTP connector settings (baseUrl, auth, headers, ...) -- see getHttp. */
    public CapabilityAst(
            String name,
            String type,
            String specializesName,
            List<CapabilityOperationAst> operations,
            OriginAst origin,
            String uid,
            Map<String, Object> http
    ) {
        this.name = name;
        this.type = type;
        this.specializesName = specializesName;
        this.operations = new ArrayList<>(operations);
        this.origin = origin;
        this.uid = uid;
        this.http = http == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(http));
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

    /** INT-1: the opaque {@code http} block of a capability bound to adapter http-connector
     *  (empty if none); shape validated by the schema and HttpConnectorValidation. */
    public Map<String, Object> getHttp() {
        return http;
    }

    public List<CapabilityOperationAst> getOperations() {
        return Collections.unmodifiableList(operations);
    }
}
