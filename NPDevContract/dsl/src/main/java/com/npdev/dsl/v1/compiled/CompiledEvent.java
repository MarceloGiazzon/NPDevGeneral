package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledEvent {
    private final String name;
    private final String conceptName;
    private final List<CompiledEventField> payload;
    private final String triggerMode;

    public CompiledEvent(String name, List<CompiledEventField> payload) {
        this(name, null, payload);
    }

    public CompiledEvent(String name, String conceptName, List<CompiledEventField> payload) {
        this(name, conceptName, payload, null);
    }

    /** @param triggerMode "create"|"update"|"delete" -- see {@link com.npdev.dsl.v1.ast.EventAst#getTriggerMode()}. */
    public CompiledEvent(String name, String conceptName, List<CompiledEventField> payload, String triggerMode) {
        this.name = name;
        this.conceptName = conceptName;
        this.payload = payload == null ? List.of() : new ArrayList<>(payload);
        this.triggerMode = triggerMode;
    }

    public String getName() { return name; }

    public String getConceptName() { return conceptName; }

    public String getTriggerMode() { return triggerMode; }

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
