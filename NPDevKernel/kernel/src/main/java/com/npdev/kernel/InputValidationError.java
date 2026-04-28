package com.npdev.kernel;

public record InputValidationError(
        String field,
        String code,
        String message
) {
}
