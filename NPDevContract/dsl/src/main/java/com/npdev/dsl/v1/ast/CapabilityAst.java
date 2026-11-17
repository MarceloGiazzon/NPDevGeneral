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
    private final Map<String, Object> inbound;
    private final String adapter;

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
        this(name, type, specializesName, operations, origin, uid, http, Map.of());
    }

    /** FORM P3-T10: the capability's inbound webhook door ({@code null} = none; an empty map is a door with every
     *  default) -- see getInbound. */
    public CapabilityAst(
            String name,
            String type,
            String specializesName,
            List<CapabilityOperationAst> operations,
            OriginAst origin,
            String uid,
            Map<String, Object> http,
            Map<String, Object> inbound
    ) {
        this(name, type, specializesName, operations, origin, uid, http, inbound, null);
    }

    /** FORM P3-T9 (D15): the adapter this capability is bound to when that is NOT a deployment default -- see getAdapter. */
    public CapabilityAst(
            String name,
            String type,
            String specializesName,
            List<CapabilityOperationAst> operations,
            OriginAst origin,
            String uid,
            Map<String, Object> http,
            Map<String, Object> inbound,
            String adapter
    ) {
        this.name = name;
        this.type = type;
        this.specializesName = specializesName;
        this.operations = new ArrayList<>(operations);
        this.origin = origin;
        this.uid = uid;
        this.http = http == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(http));
        this.adapter = adapter == null || adapter.isBlank() ? null : adapter.trim();
        this.inbound = inbound == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(inbound));
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

    /** FORM P3-T10: the opaque {@code inbound} block (path, hmacSecretEnvVar, signature, idempotencyKey,
     *  fieldMapping, eventName) that makes this capability an inbound webhook door -- empty if none or all-default.
     *  Shape validated by the schema and {@code WebhookValidation}; compiled into the internal webhook
     *  list by {@code ModelCompiler}. */
    public Map<String, Object> getInbound() {
        return inbound == null ? Map.of() : inbound;
    }

    /** FORM P3-T9 (D15): the adapter id this capability is bound to ({@code plugin:java-source}, {@code http-connector},
     *  {@code externalAi}, ...), or null. The three deployment defaults (persistence -> repository, eventBus -> inproc,
     *  a NotificationCapability -> notification-inproc) are derived by the compiler and need no declaration;
     *  {@code config.json adapters} may override them. Replaces the removed root {@code bindings[]}. */
    public String getAdapter() {
        return adapter;
    }

    /** Whether this capability declares an inbound door at all -- {@code "inbound": {}} does (every default). */
    public boolean hasInbound() {
        return inbound != null;
    }

    public List<CapabilityOperationAst> getOperations() {
        return Collections.unmodifiableList(operations);
    }
}
