package com.npdev.kernel.ports;

import com.npdev.kernel.InputValidationError;
import com.npdev.kernel.schema.SchemaObject;

import java.util.List;

public interface SchemaValidator {
    List<InputValidationError> validate(SchemaObject schema, Object payload);

    static SchemaValidator noop() {
        return (schema, payload) -> List.of();
    }
}
