package com.finalexec.npdev.service;

/**
 * ENF-1 (2026-10-24): a workbench action procedure was invoked over an aggregate whose root is in a lifecycle
 * state that declares {@code allowedActions} without it. The workbench hides such a button; the server refuses
 * the call too, so hiding is not the only guard. A client error (the controller answers 422).
 */
public final class AggregateActionNotAllowedException extends IllegalStateException {
    public AggregateActionNotAllowedException(String message) {
        super(message);
    }
}
