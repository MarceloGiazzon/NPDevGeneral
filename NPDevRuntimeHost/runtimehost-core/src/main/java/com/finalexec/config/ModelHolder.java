package com.finalexec.config;

import com.npdev.dsl.v1.compiled.CompiledModel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * B28: holds the current CompiledModel with atomic swap semantics.
 * Readers acquire a read lock (concurrent). The reload path acquires a write lock (exclusive).
 * This enables hot model reload without restarting the application.
 *
 * <p>REG-208 (B28 lift): the ONLY {@link CompiledModel} bean in a generated app -- {@code
 * NpdevCapabilityBindingConfig} builds it and immediately wraps it here; nothing else may hold one
 * directly (a direct {@code CompiledModel} injection fails to wire at startup instead of silently
 * going stale at reload time). Every other bean that needs the model holds a {@code ModelHolder}
 * and calls {@link #get()} fresh on every use -- never caches the result in a field or constructor
 * local.
 *
 * <p><b>REG-242 correction:</b> the guard above is narrower than this javadoc used to claim. It
 * catches a direct {@code CompiledModel} injection, but {@code NPDevModelProvider} is itself a
 * {@code @Component} that exposes {@code compiledModel()} -- injecting THAT compiles, wires and
 * boots cleanly, and reads the boot-time model forever, exactly the hazard this class exists to
 * prevent. {@code DocumentRenderController} did exactly this for roughly a year before it was
 * found and fixed. There is no Spring-wiring mechanism that can catch this shape; the only guard
 * is {@code scripts/quality/check-model-provider-injection.py}, which fails on any
 * {@code NPDevModelProvider} injection outside {@code
 * NpdevCapabilityBindingConfig#modelHolder} (the one seam allowed to read it, in order to seed
 * this class). If you are adding a bean that needs the model, inject {@code ModelHolder}, not
 * {@code NPDevModelProvider}.
 */
public class ModelHolder {
    private final AtomicReference<CompiledModel> current = new AtomicReference<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<ModelReloadListener> listeners = new CopyOnWriteArrayList<>();

    public ModelHolder() {
    }

    public ModelHolder(CompiledModel initialModel) {
        current.set(initialModel);
    }

    public CompiledModel get() {
        lock.readLock().lock();
        try {
            return current.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * REG-208 (B28 lift): a bean that BUILT something FROM the model at construction time (a cron
     * job's registration, a seeded menu, a cached invariant engine) needs a rebuild hook -- a fresh
     * {@link #get()} alone only helps a bean that looks the model up on every call. Registered once
     * at construction (see each implementor), notified from inside {@link #swap}'s write lock so a
     * listener observes the swap atomically with respect to concurrent readers -- calling {@link
     * #get()} from within a listener is safe (same-thread write-to-read lock downgrading is exactly
     * what {@link ReentrantReadWriteLock} is documented to support).
     */
    public interface ModelReloadListener {
        void onModelSwapped(CompiledModel before, CompiledModel after);
    }

    public void addReloadListener(ModelReloadListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Atomically swap to a new model. Blocks until all readers release. Notifies every registered
     * {@link ModelReloadListener} (in registration order) before releasing the write lock -- a
     * listener that throws propagates out of this call, surfacing as the reload endpoint's own
     * failure rather than a silently half-applied swap.
     *
     * @return the old model
     */
    public CompiledModel swap(CompiledModel newModel) {
        lock.writeLock().lock();
        try {
            CompiledModel previous = current.getAndSet(newModel);
            for (ModelReloadListener listener : listeners) {
                listener.onModelSwapped(previous, newModel);
            }
            return previous;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
