package com.npdev.runtime.support;

public interface OrchestrationExecutionRegistry {
    boolean tryAcquire(String key);

    void release(String key);
}
