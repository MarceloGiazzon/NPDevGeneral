package com.npdev.dsl.v1.ast;

public final class EventPayloadAst {
    private final String name;
    private final String type;

    public EventPayloadAst(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() { return name; }

    public String getType() { return type; }
}

