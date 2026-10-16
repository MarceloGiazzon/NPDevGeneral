package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CompiledCapability {
    private final String name;
    private final String type;
    private final List<CompiledCapabilityOperation> operations;
    private final CompiledOrigin origin;
    private final String uid;
    private final Map<String, Object> http;

    public CompiledCapability(String name, List<CompiledCapabilityOperation> operations) {
        this(name, null, operations);
    }

    public CompiledCapability(String name, String type, List<CompiledCapabilityOperation> operations) {
        this(name, type, operations, null);
    }

    /** PACK-2: attaches pack-attribution provenance (see getOrigin) -- null for an app's own root-
     *  or context-declared capability, non-null for a pack-contributed one. */
    public CompiledCapability(
            String name,
            String type,
            List<CompiledCapabilityOperation> operations,
            CompiledOrigin origin
    ) {
        this(name, type, operations, origin, null);
    }

    /** P2.1: a stable identity for this capability, generated once and never reused -- see getUid. */
    public CompiledCapability(
            String name,
            String type,
            List<CompiledCapabilityOperation> operations,
            CompiledOrigin origin,
            String uid
    ) {
        this(name, type, operations, origin, uid, Map.of());
    }

    /** INT-1: declarative HTTP connector settings -- see getHttp. */
    public CompiledCapability(
            String name,
            String type,
            List<CompiledCapabilityOperation> operations,
            CompiledOrigin origin,
            String uid,
            Map<String, Object> http
    ) {
        this.name = name;
        this.type = type;
        this.operations = new ArrayList<>(operations);
        this.origin = origin;
        this.uid = uid;
        this.http = http == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(http));
    }

    public String getName() { return name; }

    public String getType() { return type; }

    /** INT-1: the {@code http} block of a capability bound to adapter http-connector (empty if none). */
    public Map<String, Object> getHttp() {
        return http;
    }

    public List<CompiledCapabilityOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }

    /** PACK-2: pack-attribution provenance, or null if this capability is not pack-contributed. */
    public CompiledOrigin getOrigin() {
        return origin;
    }

    /** P2.1: a stable identity for this capability, generated once and never reused
     *  (npdev migrate assign-uids stamps one); null if the capability declares none. */
    public String getUid() {
        return uid;
    }
}
