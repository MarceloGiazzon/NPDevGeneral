package com.npdev.dsl.v1.ast;

public final class OrchestrationTriggerAst {
    private final String type;
    private final String event;

    public OrchestrationTriggerAst(String type, String event) {
        this.type = type;
        this.event = event;
    }

    public String getType() {
        return type;
    }

    public String getEvent() {
        return event;
    }
}
