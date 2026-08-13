package com.finalexec.config;

import com.npdev.kernel.ports.TransactionRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): the host-side {@link TransactionRunner}
 * adapter -- a {@link TransactionTemplate} wraps {@code DefaultConceptGateway.save}/{@code delete}'s
 * check-then-act critical section in a real transaction, the same {@code TransactionTemplate} (not
 * {@code @Transactional}) precedent {@code AggregateRuntime} already established for the identical
 * reason (this class is also constructed directly by non-Spring callers/tests).
 */
public final class SpringTransactionRunner implements TransactionRunner {
    private final TransactionTemplate transactionTemplate;

    public SpringTransactionRunner(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T runInTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }
}
