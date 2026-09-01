package com.finalexec.npdev.service.pluginipc;

import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixed-size pool of fungible {@link PluginIpcChildProcess} workers (SEC-3 step 3, design section 6 step
 * 3 -- docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 2). This is where the design doc's
 * cold-start-per-invoke acceptance (step 2) gets swapped for a pre-started pool, resolving the
 * npdev.runtime.plugin-timeout-ms budget concern the design doc raises against per-invoke cold starts.
 *
 * <p>Workers are FUNGIBLE, per section 2's decision: none is bound to a plugin class, so
 * {@link #invoke} always names {@code handlerClassName} explicitly, carried in the invoke frame
 * ({@link PluginIpcFrame.InvokeFrame#handlerClassName()}) a worker reads fresh on every call.</p>
 *
 * <p>Two recycling triggers, both from section 2: an invocation-count ceiling ({@code
 * maxInvocationsPerWorker}), checked after each invoke, and a wall-clock idle timer ({@code idleTimeout}),
 * checked lazily on checkout rather than by a background scheduler -- a deliberate step-3 simplification:
 * it bounds a stale worker's lifetime just as well (nothing hands out a worker that has sat idle too
 * long) without a scheduled task's own failure modes, and is what "pool mechanics, not real plugin
 * classloading yet" scope was chosen to cover (see SEC-3's ledger note on this step's scoping pass).</p>
 *
 * <p>Per design section 4 ("a replacement is started asynchronously -- not on the request's own thread")
 * a worker discovered dead or aged-out AFTER an invoke is replaced on a background thread so returning
 * that result never pays a fresh cold-start cost; a worker discovered stale BEFORE its invoke even runs
 * (the idle-timeout check on checkout) is replaced synchronously, since the calling request has no usable
 * worker to proceed with either way.</p>
 */
public final class PluginIpcChildProcessPool implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PluginIpcChildProcessPool.class.getName());
    private static final String PROCESS_KILLED_CODE = "PLUGIN_EXECUTION_PROCESS_KILLED";

    private final int maxInvocationsPerWorker;
    private final Duration idleTimeout;
    private final String classpath;
    private final PluginProcessResourceLimits resourceLimits;
    private final BlockingQueue<PooledWorker> idleWorkers = new LinkedBlockingQueue<>();
    private final ExecutorService replacementExecutor = Executors.newSingleThreadExecutor(PluginIpcChildProcessPool::newDaemonThread);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public PluginIpcChildProcessPool(int poolSize, int maxInvocationsPerWorker, Duration idleTimeout) throws IOException {
        this(poolSize, maxInvocationsPerWorker, idleTimeout, System.getProperty("java.class.path"));
    }

    public PluginIpcChildProcessPool(
            int poolSize, int maxInvocationsPerWorker, Duration idleTimeout, String classpath
    ) throws IOException {
        this(poolSize, maxInvocationsPerWorker, idleTimeout, classpath, PluginProcessResourceLimits.NONE);
    }

    /** Same as the four-arg constructor, additionally applying an OS-level resource ceiling (SEC-3 step 4)
     * to every worker this pool spawns, including replacements. */
    public PluginIpcChildProcessPool(
            int poolSize, int maxInvocationsPerWorker, Duration idleTimeout, String classpath, PluginProcessResourceLimits resourceLimits
    ) throws IOException {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1, got " + poolSize);
        }
        if (maxInvocationsPerWorker < 1) {
            throw new IllegalArgumentException("maxInvocationsPerWorker must be >= 1, got " + maxInvocationsPerWorker);
        }
        this.maxInvocationsPerWorker = maxInvocationsPerWorker;
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.classpath = Objects.requireNonNull(classpath, "classpath");
        this.resourceLimits = Objects.requireNonNull(resourceLimits, "resourceLimits");
        for (int i = 0; i < poolSize; i++) {
            idleWorkers.add(new PooledWorker(PluginIpcChildProcess.startPooled(classpath, resourceLimits)));
        }
    }

    /**
     * Checks out a worker (synchronously replacing it first if it has sat idle past {@code idleTimeout}),
     * runs one invocation against it, then either returns it to the pool or -- if it died mid-invoke or
     * just hit its invocation ceiling -- closes it and queues a background replacement.
     */
    public CapabilityResult invoke(
            PluginIpcHostSession hostSession,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            CapabilityCall call,
            Map<String, Object> contextState,
            String handlerClassName
    ) throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("Plugin IPC child process pool is closed");
        }
        PooledWorker worker = checkout();
        CapabilityResult result;
        try {
            result = worker.process.invoke(hostSession, contribution, call, contextState, handlerClassName);
        } catch (RuntimeException exception) {
            retireAndReplaceAsync(worker);
            throw exception;
        }
        worker.invocationCount++;
        boolean workerDied = !result.ok()
                && result.error() != null
                && PROCESS_KILLED_CODE.equals(result.error().code());
        if (workerDied || worker.invocationCount >= maxInvocationsPerWorker) {
            retireAndReplaceAsync(worker);
        } else {
            returnToPool(worker);
        }
        return result;
    }

    private PooledWorker checkout() throws InterruptedException {
        PooledWorker worker = idleWorkers.take();
        if (!isIdleTooLong(worker)) {
            return worker;
        }
        worker.process.close();
        try {
            return new PooledWorker(PluginIpcChildProcess.startPooled(classpath, resourceLimits));
        } catch (IOException exception) {
            throw new UncheckedPluginIpcPoolException("Failed to replace an idle-timed-out plugin IPC pooled worker", exception);
        }
    }

    private boolean isIdleTooLong(PooledWorker worker) {
        return Duration.between(worker.lastReturnedToPool, Instant.now()).compareTo(idleTimeout) > 0;
    }

    private void returnToPool(PooledWorker worker) {
        if (closed.get()) {
            worker.process.close();
            return;
        }
        worker.lastReturnedToPool = Instant.now();
        idleWorkers.add(worker);
    }

    private void retireAndReplaceAsync(PooledWorker worker) {
        worker.process.close();
        if (closed.get()) {
            return;
        }
        replacementExecutor.submit(() -> {
            try {
                idleWorkers.add(new PooledWorker(PluginIpcChildProcess.startPooled(classpath, resourceLimits)));
            } catch (IOException exception) {
                LOG.log(Level.SEVERE, "Failed to spawn a replacement plugin IPC pooled worker", exception);
            }
        });
    }

    /** Idle workers only, per design section 2's own stated scope -- no in-flight-request draining. */
    public int idleWorkerCount() {
        return idleWorkers.size();
    }

    /**
     * Closes every currently-idle worker (graceful signal, grace period, then force-kill -- see {@link
     * PluginIpcChildProcess#close()}) and stops accepting new invocations. Matches design section 2's own
     * stated scope: no draining of workers already checked out for an in-flight invoke, "no more than
     * TimeBoundedPluginExecutionEngine's own executorService.shutdownNow() already does for the host side
     * today."
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        replacementExecutor.shutdownNow();
        PooledWorker worker;
        while ((worker = idleWorkers.poll()) != null) {
            worker.process.close();
        }
    }

    private static Thread newDaemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "plugin-ipc-pool-replacement");
        thread.setDaemon(true);
        return thread;
    }

    private static final class PooledWorker {
        private final PluginIpcChildProcess process;
        private int invocationCount;
        private Instant lastReturnedToPool = Instant.now();

        private PooledWorker(PluginIpcChildProcess process) {
            this.process = process;
        }
    }

    public static final class UncheckedPluginIpcPoolException extends RuntimeException {
        UncheckedPluginIpcPoolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
