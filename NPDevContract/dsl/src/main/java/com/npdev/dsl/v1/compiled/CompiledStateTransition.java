package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CompiledStateTransition {
    private final String from;
    private final String to;
    private final List<String> requiredPayload;
    private final String event;
    private final String guard;
    private final String actionLabel;
    private final Map<String, String> metadata;
    private final CompiledActionMetadata action;
    private final Map<String, String> actionLabelLocales;

    public CompiledStateTransition(String from, String to, List<String> requires) {
        this(from, to, requires, null);
    }

    public CompiledStateTransition(String from, String to, List<String> requires, String event) {
        this(from, to, requires, event, null, null, Map.of(), null);
    }

    public CompiledStateTransition(
            String from,
            String to,
            List<String> requiredPayload,
            String event,
            String guard,
            String actionLabel,
            Map<String, String> metadata
    ) {
        this(from, to, requiredPayload, event, guard, actionLabel, metadata, null);
    }

    /** R5.6: pre-existing 8-arg shape, kept so callers built against it (e.g. runtimehost test
     *  fixtures constructing this record positionally) keep compiling unchanged. */
    public CompiledStateTransition(
            String from,
            String to,
            List<String> requiredPayload,
            String event,
            String guard,
            String actionLabel,
            Map<String, String> metadata,
            CompiledActionMetadata action
    ) {
        this(from, to, requiredPayload, event, guard, actionLabel, metadata, action, Map.of());
    }

    public CompiledStateTransition(
            String from,
            String to,
            List<String> requiredPayload,
            String event,
            String guard,
            String actionLabel,
            Map<String, String> metadata,
            CompiledActionMetadata action,
            Map<String, String> actionLabelLocales
    ) {
        this.from = from;
        this.to = to;
        this.requiredPayload = requiredPayload == null ? List.of() : new ArrayList<>(requiredPayload);
        this.event = event;
        this.guard = guard;
        this.actionLabel = actionLabel;
        this.metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
        this.action = action;
        this.actionLabelLocales = (actionLabelLocales == null || actionLabelLocales.isEmpty()) ? Map.of() : Map.copyOf(actionLabelLocales);
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public List<String> getRequiredPayload() {
        return Collections.unmodifiableList(requiredPayload);
    }

    public List<String> getRequires() {
        return getRequiredPayload();
    }

    public String getEvent() {
        return event;
    }

    public String getGuard() {
        return guard;
    }

    public String getActionLabel() {
        return actionLabel;
    }

    public Map<String, String> getActionLabelLocales() {
        return actionLabelLocales;
    }

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public CompiledActionMetadata getAction() {
        return action;
    }
}
