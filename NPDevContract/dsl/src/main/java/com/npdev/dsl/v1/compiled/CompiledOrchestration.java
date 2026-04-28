package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledOrchestration {
    private final String name;
    private final String condition;
    private final CompiledOrchestrationTrigger trigger;
    private final CompiledOrchestrationAction action;
    private final List<CompiledOrchestrationAction> actions;

    public CompiledOrchestration(
            String name,
            CompiledOrchestrationTrigger trigger,
            CompiledOrchestrationAction action
    ) {
        this(name, null, trigger, action, action == null ? List.of() : List.of(action));
    }

    public CompiledOrchestration(
            String name,
            String condition,
            CompiledOrchestrationTrigger trigger,
            CompiledOrchestrationAction action
    ) {
        this(name, condition, trigger, action, action == null ? List.of() : List.of(action));
    }

    public CompiledOrchestration(
            String name,
            String condition,
            CompiledOrchestrationTrigger trigger,
            List<CompiledOrchestrationAction> actions
    ) {
        this(name, condition, trigger, firstOrNull(actions), actions);
    }

    public CompiledOrchestration(
            String name,
            String condition,
            CompiledOrchestrationTrigger trigger,
            CompiledOrchestrationAction action,
            List<CompiledOrchestrationAction> actions
    ) {
        this.name = name;
        this.condition = condition;
        this.trigger = trigger;
        this.action = action;
        List<CompiledOrchestrationAction> sequence = new ArrayList<>();
        if (actions != null && !actions.isEmpty()) {
            for (CompiledOrchestrationAction candidate : actions) {
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

    public CompiledOrchestrationTrigger getTrigger() {
        return trigger;
    }

    public CompiledOrchestrationAction getAction() {
        return action;
    }

    public List<CompiledOrchestrationAction> getActions() {
        return actions;
    }

    private static CompiledOrchestrationAction firstOrNull(List<CompiledOrchestrationAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        return actions.get(0);
    }
}
