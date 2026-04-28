package com.npdev.dsl.v1.compiled;

public final class CompiledOrchestrationTrigger {
    private final String type;
    private final String event;

    public CompiledOrchestrationTrigger(String type, String event) {
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
