package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * W3.3 (2026-08-25 remediation plan / QUAL-33): extracted from the private {@code EventStoreStub}
 * nested class KernelRunnerTest carried (the only in-memory EventBus+EventStore fake in the repo) so
 * other tests -- starting with EventStorePerformanceRegressionTest -- can use it without duplicating
 * it. Thread-safe (CopyOnWriteArrayList) since it is also used to exercise concurrent append paths.
 */
public final class InMemoryEventStore implements EventBus, EventStore {
    private final List<EventEnvelope> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(EventEnvelope event) {
        append(event);
    }

    @Override
    public void append(EventEnvelope event) {
        events.add(event);
    }

    @Override
    public Optional<EventEnvelope> findFirst(String eventName, String correlationId) {
        return events.stream()
                .filter(event -> eventName.equals(event.eventName()) && correlationId.equals(event.correlationId()))
                .findFirst();
    }

    @Override
    public List<EventEnvelope> readByCorrelation(String correlationId) {
        return events.stream()
                .filter(event -> correlationId.equals(event.correlationId()))
                .toList();
    }

    @Override
    public List<EventEnvelope> readByEventName(String eventName) {
        return events.stream()
                .filter(event -> eventName.equals(event.eventName()))
                .toList();
    }

    public int size() {
        return events.size();
    }

    public List<EventEnvelope> all() {
        return new ArrayList<>(events);
    }
}
