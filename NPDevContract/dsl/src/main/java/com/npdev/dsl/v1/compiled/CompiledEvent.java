package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledEvent {
    private final String name;
    private final String conceptName;
    private final List<CompiledEventField> payload;

    public CompiledEvent(String name, List<CompiledEventField> payload) {
        this(name, null, payload);
    }

    public CompiledEvent(String name, String conceptName, List<CompiledEventField> payload) {
        this.name = name;
        this.conceptName = conceptName;
        this.payload = payload == null ? List.of() : new ArrayList<>(payload);
    }

    public String getName() { return name; }

    public String getConceptName() { return conceptName; }

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
