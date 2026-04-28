package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OrchestrationAst {
    private final String name;
    private final String condition;
    private final OrchestrationTriggerAst trigger;
    private final OrchestrationActionAst action;
    private final List<OrchestrationActionAst> actions;

    public OrchestrationAst(String name, OrchestrationTriggerAst trigger, OrchestrationActionAst action) {
        this(name, null, trigger, action, action == null ? List.of() : List.of(action));
    }

    public OrchestrationAst(
            String name,
            String condition,
            OrchestrationTriggerAst trigger,
            OrchestrationActionAst action
    ) {
        this(name, condition, trigger, action, action == null ? List.of() : List.of(action));
    }

    public OrchestrationAst(
            String name,
            String condition,
            OrchestrationTriggerAst trigger,
            List<OrchestrationActionAst> actions
    ) {
        this(name, condition, trigger, firstOrNull(actions), actions);
    }

    public OrchestrationAst(
            String name,
            String condition,
            OrchestrationTriggerAst trigger,
            OrchestrationActionAst action,
            List<OrchestrationActionAst> actions
    ) {
        this.name = name;
        this.condition = condition;
        this.trigger = trigger;
        this.action = action;
        List<OrchestrationActionAst> sequence = new ArrayList<>();
        if (actions != null && !actions.isEmpty()) {
            for (OrchestrationActionAst candidate : actions) {
                if (candidate != null) {
                    sequence.add(candidate);
                }
            }
        } else if (action != null) {
            sequence.add(action);
        }
        this.actions = Collections.unmodifiableList(sequence);
    }

    public String getName() {
        return name;
    }

    public String getCondition() {
        return condition;
    }

    public OrchestrationTriggerAst getTrigger() {
        return trigger;
    }

    public OrchestrationActionAst getAction() {
        return action;
    }

    public List<OrchestrationActionAst> getActions() {
        return actions;
    }

    private static OrchestrationActionAst firstOrNull(List<OrchestrationActionAst> actions) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        return actions.get(0);
    }
}
