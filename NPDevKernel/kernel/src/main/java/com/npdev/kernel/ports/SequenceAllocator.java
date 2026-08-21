package com.npdev.kernel.ports;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R5.3: atomically allocates the NEXT integer in a named counter, for the model's {@code
 * sequences[]}/{@code nextNumber('name')} declarative-numbering feature.
 *
 * <p>{@code scopeKey} is the FULL partition key -- sequence name, plus a tenant segment when the
 * sequence is {@code scope: "tenant"}, plus any date-bucket implied by the sequence's own {@code
 * format} (see {@code com.npdev.dsl.v1.expr.SequenceNumberFormat#scopeKeySuffix}) -- already
 * composed by the caller ({@code ConfiguredConceptGatewaySemanticPolicy}), so this port itself
 * carries no notion of tenancy, date, or rendering; it only ever answers "give me the next integer
 * for this exact key".
 *
 * <p>Kernel stays framework/JDBC-agnostic -- this port exists precisely so {@code
 * ConfiguredConceptGatewaySemanticPolicy} never imports JDBC, the same reasoning {@link
 * TransactionRunner} already established for the transaction boundary. {@link #inMemory()} is the
 * default every existing caller keeps using -- correct for the {@code *-inproc} adapters and any
 * test that does not need cross-process durability (same "correct for in-proc, real pushdown on
 * JDBC" shape as {@link ConceptStore#existsUnique}). A database-backed implementation (see {@code
 * JdbcSequenceAllocator}, RuntimeHost) persists the counter in {@code npdev_sequence_counter} and
 * allocates under a real row lock, INSIDE the same ambient transaction {@code
 * DefaultConceptGateway}'s {@link TransactionRunner} opened for the concept row this default is
 * being computed for.
 */
public interface SequenceAllocator {

    long allocateNext(String scopeKey);

    static SequenceAllocator inMemory() {
        ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
        return scopeKey -> counters.computeIfAbsent(scopeKey, key -> new AtomicLong(0)).incrementAndGet();
    }
}
