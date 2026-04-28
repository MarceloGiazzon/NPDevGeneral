package com.npdev.adapters.events.inproc;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process event bus adapter with publish + subscribe support.
 */
public final class InProcEventBus implements EventBus {

    private final Map<String, CopyOnWriteArrayList<EventHandler>> handlersByEventName = new ConcurrentHashMap<>();

    @Override
    public void publish(EventEnvelope event) {
        Objects.requireNonNull(event, "event");

        List<EventHandler> handlers = new ArrayList<>(handlersByEventName.getOrDefault(
                event.eventName(),
                new CopyOnWriteArrayList<>()
        ));
        for (EventHandler handler : handlers) {
            handler.onEvent(event);
        }
    }

    @Override
    public AutoCloseable subscribe(String eventName, EventHandler handler) {
        Objects.requireNonNull(eventName, "eventName");
        Objects.requireNonNull(handler, "handler");
        CopyOnWriteArrayList<EventHandler> handlers =
                handlersByEventName.computeIfAbsent(eventName, t -> new CopyOnWriteArrayList<>());
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }
}
