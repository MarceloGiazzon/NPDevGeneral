package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledEvent {
    private final String name;
    private final String conceptName;
    private final List<CompiledEventField> payload;
    private final String triggerMode;
    private final CompiledOrigin origin;
    private final String uid;

    public CompiledEvent(String name, List<CompiledEventField> payload) {
        this(name, null, payload);
    }

    public CompiledEvent(String name, String conceptName, List<CompiledEventField> payload) {
        this(name, conceptName, payload, null);
    }

    /** @param triggerMode "create"|"update"|"delete" -- see {@link com.npdev.dsl.v1.ast.EventAst#getTriggerMode()}. */
    public CompiledEvent(String name, String conceptName, List<CompiledEventField> payload, String triggerMode) {
        this(name, conceptName, payload, triggerMode, null);
    }

    /** PACK-2: attaches pack-attribution provenance (see getOrigin) -- null for an app's own root-
     *  or concept-nested event, non-null for a pack-contributed top-level event. */
    public CompiledEvent(
            String name,
            String conceptName,
            List<CompiledEventField> payload,
            String triggerMode,
            CompiledOrigin origin
    ) {
        this(name, conceptName, payload, triggerMode, origin, null);
    }

    /** P2.1: a stable identity for this event, generated once and never reused -- see getUid. */
    public CompiledEvent(
            String name,
            String conceptName,
            List<CompiledEventField> payload,
            String triggerMode,
            CompiledOrigin origin,
            String uid
    ) {
        this.name = name;
        this.conceptName = conceptName;
        this.payload = payload == null ? List.of() : new ArrayList<>(payload);
        this.triggerMode = triggerMode;
        this.origin = origin;
        this.uid = uid;
    }

    public String getName() { return name; }

    public String getConceptName() { return conceptName; }

    public String getTriggerMode() { return triggerMode; }

    /** PACK-2: pack-attribution provenance, or null if this event is not pack-contributed. */
    public CompiledOrigin getOrigin() { return origin; }

    /** P2.1: a stable identity for this event, generated once and never reused
     *  (npdev migrate assign-uids stamps one); null if the event declares none. */
    public String getUid() { return uid; }

    public List<CompiledEventField> getPayloadFields() {
        return Collections.unmodifiableList(payload);
    }

    // Backward compatible accessor (payload names only).
    public List<String> getPayload() {
        List<String> out = new ArrayList<>();
        for (CompiledEventField field : payload) {
            out.add(field.getName());
        }
        return Collections.unmodifiableList(out);
    }
}
