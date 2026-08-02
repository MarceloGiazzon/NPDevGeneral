package com.npdev.kernel.properties;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProperty;
import com.npdev.dsl.v1.compiled.CompiledPropertyScope;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGateways;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.ports.AuditLogStore;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vector 15: "a write invalidates the cache ... prove under concurrent load, do not assert it."
 * Two separate claims, proven separately:
 *
 * <ol>
 *   <li>{@link #cachingActuallyReducesGatewayQueries()} -- the cache has an effect at all (a repeated
 *       resolve() for the SAME key does not re-query the gateway), single-threaded, cheap.</li>
 *   <li>{@link #concurrentReadsDuringAWriteNeverObserveACorruptedOrPermanentlyStaleValue()} -- many
 *       threads calling resolve() in a tight loop while another thread performs a write, repeated
 *       ({@code @RepeatedTest}) to make a race window more likely to be hit, asserting every reader
 *       eventually converges on the new value and none throws.</li>
 * </ol>
 */
class PropertyResolverCacheConcurrencyTest {

    private static final CompiledPropertyScope TENANT_SCOPE = new CompiledPropertyScope("tenant", null);
    private static final CompiledProperty PAGE_ROWS =
            new CompiledProperty("pageRows", "int", 25, List.of("tenant"), null, false);
    private static final ExecutionContext CTX = ExecutionContext.of("t1", "actor-1");

    private static CompiledModel model() {
        return new CompiledModel(
                "wms.props", "1.0.0", "1.0",
                Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(),
                List.of(TENANT_SCOPE),
                List.of(PAGE_ROWS)
        );
    }

    @Test
    void cachingActuallyReducesGatewayQueries() {
        CountingConceptGateway gateway = new CountingConceptGateway(ConceptGateways.inMemory());
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        resolver.resolve("pageRows", CTX);
        resolver.resolve("pageRows", CTX);
        resolver.resolve("pageRows", CTX);
        assertEquals(1, gateway.queryCount.get(), "a repeated resolve() for the same key must hit the cache, not the gateway");

        int queriesBeforeWrite = gateway.queryCount.get();
        resolver.set("tenant", "t1", "pageRows", "50", CTX); // set() itself queries once (read-before-write)
        resolver.resolve("pageRows", CTX);
        assertEquals(queriesBeforeWrite + 2, gateway.queryCount.get(),
                "a write must invalidate the cache -- the next resolve() re-queries (+1 for set()'s own read-before-write, +1 for the fresh resolve)");
    }

    @RepeatedTest(20)
    @Timeout(30)
    void concurrentReadsDuringAWriteNeverObserveACorruptedOrPermanentlyStaleValue() throws InterruptedException {
        ConceptGateway gateway = ConceptGateways.inMemory();
        PropertyResolver resolver = new DefaultPropertyResolver(gateway, AuditLogStore.noop(), model());

        int readerCount = 8;
        int iterationsPerReader = 500;
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger unexpectedValues = new AtomicInteger();
        AtomicInteger exceptions = new AtomicInteger();
        AtomicReference<Object> lastObservedBeforeWriteSettles = new AtomicReference<>();

        Runnable reader = () -> {
            try {
                start.await();
                for (int i = 0; i < iterationsPerReader; i++) {
                    Object value = resolver.resolve("pageRows", CTX);
                    if (!Integer.valueOf(25).equals(value) && !Integer.valueOf(50).equals(value)) {
                        unexpectedValues.incrementAndGet();
                    }
                    lastObservedBeforeWriteSettles.set(value);
                }
            } catch (Exception e) {
                exceptions.incrementAndGet();
            }
        };
        Runnable writer = () -> {
            try {
                start.await();
                Thread.sleep(5); // let readers warm the cache first
                resolver.set("tenant", "t1", "pageRows", "50", CTX);
            } catch (Exception e) {
                exceptions.incrementAndGet();
            }
        };

        for (int i = 0; i < readerCount; i++) {
            pool.submit(reader);
        }
        pool.submit(writer);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "readers/writer did not finish in time");

        assertEquals(0, exceptions.get(), "no reader/writer thread may throw");
        assertEquals(0, unexpectedValues.get(), "every observed value must be a real cascade value (25 or 50), never corrupted");

        // Final, single-threaded resolve() after all concurrent activity settles: must reflect the
        // write, not a permanently-stale cached 25 -- this is the actual invalidation claim.
        Object finalValue = resolver.resolve("pageRows", CTX);
        assertEquals(50, finalValue, "after the write settles, resolution must converge on the new value, not stay stuck on a stale cache entry");
    }

    /** Delegates every call; counts only {@link #query}, the one path {@link DefaultPropertyResolver} reads through. */
    private static final class CountingConceptGateway implements ConceptGateway {
        private final ConceptGateway delegate;
        private final AtomicInteger queryCount = new AtomicInteger();

        CountingConceptGateway(ConceptGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            return delegate.read(request, context);
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            return delegate.list(request, context);
        }

        @Override
        public ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
            queryCount.incrementAndGet();
            return delegate.query(request, context);
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            return delegate.save(request, context);
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            delegate.delete(request, context);
        }
    }
}
