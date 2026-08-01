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

    static TransactionRunner none() {
        return new TransactionRunner() {
            @Override
            public <T> T runInTransaction(Supplier<T> action) {
                return action.get();
            }
        };
    }
}
