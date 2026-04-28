package com.npdev.kernel.ports;

import com.npdev.kernel.events.EventEnvelope;

public interface EventBus {

    void publish(EventEnvelope event);

    default AutoCloseable subscribe(String eventName, EventHandler handler) {
        return () -> {
        };
    }

    @FunctionalInterface
    interface EventHandler {
        void onEvent(EventEnvelope event);
    }
}
