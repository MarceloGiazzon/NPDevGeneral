package com.npdev.runtime.support;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryOrchestrationExecutionRegistry implements OrchestrationExecutionRegistry {

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    @Override
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return keys.add(key.trim());
    }

    @Override
    public void release(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        keys.remove(key.trim());
    }
}
