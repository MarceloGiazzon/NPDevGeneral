package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EventAst {
    private final String name;
    private final String conceptName;
    private final String specializesName;
    private final String version;
    private final List<EventPayloadAst> payload;
    private final String triggerMode;

    public EventAst(String name, List<EventPayloadAst> payload) {
        this(name, null, null, null, payload);
    }

    public EventAst(String name, String conceptName, List<EventPayloadAst> payload) {
        this(name, conceptName, null, null, payload);
    }

    public EventAst(
            String name,
            String conceptName,
            String specializesName,
            String version,
            List<EventPayloadAst> payload
    ) {
        this(name, conceptName, specializesName, version, payload, null);
    }

    /**
     * @param triggerMode "create"|"update"|"delete" if this concept-nested event should be
     *                    published directly from generated CRUD's matching mutation step (in
     *                    addition to any Flow's own emitEvent step); null/blank for an event that
     *                    is only ever published explicitly (e.g. from a Flow).
     */
    public EventAst(
            String name,
            String conceptName,
            String specializesName,
            String version,
            List<EventPayloadAst> payload,
            String triggerMode
    ) {
        this.name = name;
        this.conceptName = conceptName;
        this.specializesName = specializesName;
        this.version = version;
        this.payload = payload == null ? List.of() : new ArrayList<>(payload);
        this.triggerMode = triggerMode;
    }

    public String getName() { return name; }

    public String getConceptName() { return conceptName; }

    public String getSpecializesName() {
        return specializesName;
    }

    public String getVersion() {
        return version;
    }

    public String getTriggerMode() {
        return triggerMode;
    }

    public List<EventPayloadAst> getPayloadFields() {
        return Collections.unmodifiableList(payload);
    }

    // Backward compatible accessor used by existing code/tests.
    public List<String> getPayload() {
        List<String> out = new ArrayList<>();
        for (EventPayloadAst field : payload) {
            out.add(field.getName());
        }
        return Collections.unmodifiableList(out);
    }
}
