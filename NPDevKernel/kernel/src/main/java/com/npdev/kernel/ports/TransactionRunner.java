package com.npdev.kernel.ports;

import java.util.function.Supplier;

/**
 * B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): runs a unit of work atomically when a real
 * transaction manager is available, and directly (no transaction) otherwise. Kernel stays
 * framework-agnostic -- this port exists precisely so {@link com.npdev.kernel.concepts.DefaultConceptGateway}
 * never imports Spring; the host wires the real implementation (a {@code TransactionTemplate}, the
 * same precedent {@code AggregateRuntime} already established) at the edge.
 *
 * <p>{@link #none()} is the default every existing {@code DefaultConceptGateway} constructor keeps
 * using -- a plain passthrough, so nothing about today's (non-transactional) behavior changes for a
 * caller that does not explicitly wire a real one in.
 */
public interface TransactionRunner {

    <T> T runInTransaction(Supplier<T> action);

    /**
     * REG-210: whether this runner actually provides a transaction manager. {@code false} ONLY for
     * {@link #none()} -- the degraded, no-transaction-manager mode, where there is no transactional
     * resource to lock and the callee must fall back to optimistic compare-and-swap. A real
     * implementation (a {@code TransactionTemplate} wrapper) inherits {@code true}, so existing
     * hosts that wire one in get the pessimistic-lock path without change.
     */
    default boolean isTransactional() {
        return true;
    }

    static TransactionRunner none() {
        return new TransactionRunner() {
            @Override
            public <T> T runInTransaction(Supplier<T> action) {
                return action.get();
            }

            @Override
            public boolean isTransactional() {
                return false;
            }
        };
    }
}
