package com.npdev.adapters.runtime.validation;

public final class StrictExecutionViolationException extends IllegalStateException {

    public StrictExecutionViolationException(String message) {
        super(message);
    }

    public StrictExecutionViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
