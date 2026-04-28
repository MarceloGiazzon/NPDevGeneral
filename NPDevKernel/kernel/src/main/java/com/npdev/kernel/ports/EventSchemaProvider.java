package com.npdev.kernel.ports;

import com.npdev.kernel.schema.SchemaObject;

import java.util.Optional;

@FunctionalInterface
public interface EventSchemaProvider {
    Optional<SchemaObject> findEventPayloadSchema(String eventName);

    static EventSchemaProvider noop() {
        return eventName -> Optional.empty();
    }
}
