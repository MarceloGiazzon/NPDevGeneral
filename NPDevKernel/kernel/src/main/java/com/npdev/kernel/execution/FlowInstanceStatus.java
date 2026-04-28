package com.npdev.kernel.execution;

public enum FlowInstanceStatus {
    RUNNING,
    WAITING_EVENT,
    COMPLETED,
    FAILED,
    FAILED_PERMANENT,
    STUCK
}
