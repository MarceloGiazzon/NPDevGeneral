package com.npdev.kernel;

public enum ExecutionStatus {
    OK,
    INPUT_VALIDATION_FAILED,
    WAITING_EVENT,
    EVENT_PERSIST_FAILED,
    EVENT_PAYLOAD_INVALID,
    INVARIANT_FAILED,
    CAPABILITY_FAILED,
    FAILED
}
