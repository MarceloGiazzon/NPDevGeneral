package com.npdev.dsl.v1.compiled;

public final class CompiledEventField {
    private final String name;
    private final String type;

    public CompiledEventField(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() { return name; }

    public String getType() { return type; }
}

