package com.npdev.dsl.v1.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OrchestrationActionAst {
    private final String type;
    private final String concept;
    private final String capability;
    private final String operation;
    private final String event;
    private final Long delaySeconds;
    private final Map<String, String> map;
    private final ActionMetadataAst action;

    public OrchestrationActionAst(String type, String concept, Map<String, String> map) {
        this(type, concept, null, null, null, null, map, null);
    }

    public OrchestrationActionAst(
            String type,
            String concept,
            String capability,
            String operation,
            Map<String, String> map
    ) {
        this(type, concept, capability, operation, null, null, map, null);
    }

    public OrchestrationActionAst(
            String type,
            String concept,
            String capability,
            String operation,
            String event,
            Long delaySeconds,
            Map<String, String> map
    ) {
        this(type, concept, capability, operation, event, delaySeconds, map, null);
    }

    public OrchestrationActionAst(
            String type,
            String concept,
            String capability,
            String operation,
            String event,
            Long delaySeconds,
            Map<String, String> map,
            ActionMetadataAst action
    ) {
        this.type = type;
        this.concept = concept;
        this.capability = capability;
        this.operation = operation;
        this.event = event;
        this.delaySeconds = delaySeconds;
        this.map = map == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(map));
        this.action = action;
    }

    public String getType() {
        return type;
    }

    public String getConcept() {
        return concept;
    }

    public String getCapability() {
        return capability;
    }

    public String getOperation() {
        return operation;
    }

    public String getEvent() {
        return event;
    }

    public Long getDelaySeconds() {
        return delaySeconds;
    }

    public Map<String, String> getMap() {
        return map;
    }

    public ActionMetadataAst getAction() {
        return action;
    }
}
