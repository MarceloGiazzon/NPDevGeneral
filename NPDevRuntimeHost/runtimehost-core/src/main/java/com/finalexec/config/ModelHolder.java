package com.finalexec.config;

import com.npdev.dsl.v1.compiled.CompiledModel;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * B28: holds the current CompiledModel with atomic swap semantics.
 * Readers acquire a read lock (concurrent). The reload path acquires a write lock (exclusive).
 * This enables hot model reload without restarting the application.
 */
public class ModelHolder {
    private final AtomicReference<CompiledModel> current = new AtomicReference<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

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
     * Atomically swap to a new model. Blocks until all readers release.
     * Returns the old model.
     */
    public CompiledModel swap(CompiledModel newModel) {
        lock.writeLock().lock();
        try {
            return current.getAndSet(newModel);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
