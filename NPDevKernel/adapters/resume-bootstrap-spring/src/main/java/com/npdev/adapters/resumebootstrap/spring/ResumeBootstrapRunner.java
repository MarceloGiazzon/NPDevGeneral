package com.npdev.adapters.resumebootstrap.spring;

import com.npdev.kernel.KernelRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.Objects;

/**
 * Startup-only recovery hook:
 * once the app is ready, ask kernel to resume waiting executions persisted in stores.
 */
public final class ResumeBootstrapRunner {
    private final KernelRunner kernelRunner;
    private final int resumeLimit;

    public ResumeBootstrapRunner(KernelRunner kernelRunner, int resumeLimit) {
        this.kernelRunner = Objects.requireNonNull(kernelRunner, "kernelRunner");
        this.resumeLimit = resumeLimit <= 0 ? 1000 : resumeLimit;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        kernelRunner.resumeAllWaitingExecutions(resumeLimit);
    }
}
