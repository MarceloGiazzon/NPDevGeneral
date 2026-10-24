package com.finalexec.npdev.service;

/**
 * REG-265: an aggregate commit refused because the DRAFT breaks a rule the aggregate declares -- a
 * violated {@code invariants[]} entry, an out-of-balance {@code balances[]} group, or (ENF-1) a declared
 * {@code onValidate} procedure that did not return ok for the draft. That is a
 * client error (the caller sent a draft the model forbids), unlike the plain
 * {@link IllegalStateException}s this class extends, which stay "the runtime cannot serve this
 * right now" (no gateway, failing onCommit procedure, ...). Subclassing keeps every existing
 * {@code catch (IllegalStateException)} and message assertion working; the controller catches this
 * subtype first and answers a 4xx.
 */
public final class AggregateCommitVetoException extends IllegalStateException {
    public AggregateCommitVetoException(String message) {
        super(message);
    }
}
