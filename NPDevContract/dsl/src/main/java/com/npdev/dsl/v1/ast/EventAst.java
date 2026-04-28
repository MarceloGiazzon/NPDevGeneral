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
        this.name = name;
        this.conceptName = conceptName;
        this.specializesName = specializesName;
        this.version = version;
        this.payload = payload == null ? List.of() : new ArrayList<>(payload);
    }

    public String getName() { return name; }

    public String getConceptName() { return conceptName; }

    public String getSpecializesName() {
        return specializesName;
    }

    public String getVersion() {
        return version;
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
