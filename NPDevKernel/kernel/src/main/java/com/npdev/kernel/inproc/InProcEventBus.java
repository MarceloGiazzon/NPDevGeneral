package com.npdev.kernel.inproc;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-process event bus used by deterministic MVP execution and tests.
 */
public final class InProcEventBus implements EventBus {

    private final Map<String, List<EventHandler>> subscribersByEvent = new LinkedHashMap<>();
    private final List<EventEnvelope> publishedEvents = new ArrayList<>();

    @Override
    public synchronized void publish(EventEnvelope event) {
        Objects.requireNonNull(event, "event");
        publishedEvents.add(event);

        List<EventHandler> exactHandlers = new ArrayList<>(
                subscribersByEvent.getOrDefault(event.eventName(), List.of())
        );
        List<EventHandler> wildcardHandlers = new ArrayList<>(
                subscribersByEvent.getOrDefault("*", List.of())
        );
        for (EventHandler handler : exactHandlers) {
            handler.onEvent(event);
        }
        for (EventHandler handler : wildcardHandlers) {
            handler.onEvent(event);
        }
    }

    @Override
    public synchronized AutoCloseable subscribe(String eventName, EventHandler handler) {
        Objects.requireNonNull(handler, "handler");
        String topic = normalizeTopic(eventName);
        subscribersByEvent.computeIfAbsent(topic, ignored -> new ArrayList<>()).add(handler);
        return () -> unsubscribe(topic, handler);
    }

    public synchronized List<EventEnvelope> publishedEvents() {
        return List.copyOf(publishedEvents);
    }

    private synchronized void unsubscribe(String topic, EventHandler handler) {
        List<EventHandler> handlers = subscribersByEvent.get(topic);
        if (handlers == null) {
            return;
        }
        handlers.remove(handler);
        if (handlers.isEmpty()) {
            subscribersByEvent.remove(topic);
        }
    }

    private static String normalizeTopic(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return "*";
        }
        return eventName.trim();
    }
}
