package com.finalexec.debug;

import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.TraceStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class StorageWiringLogger implements ApplicationRunner {

    private final Environment environment;
    private final TraceStore traceStore;
    private final ExecutionTracer executionTracer;
    private final FlowInstanceStore flowInstanceStore;

    public StorageWiringLogger(
            Environment environment,
            TraceStore traceStore,
            ExecutionTracer executionTracer,
            FlowInstanceStore flowInstanceStore
    ) {
        this.environment = environment;
        this.traceStore = traceStore;
        this.executionTracer = executionTracer;
        this.flowInstanceStore = flowInstanceStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("=== NPDev Storage Wiring ===");
        System.out.println("Active profiles: " + Arrays.toString(environment.getActiveProfiles()));
        System.out.println("TraceStore: " + traceStore.getClass().getName());
        System.out.println("ExecutionTracer: " + executionTracer.getClass().getName());
        System.out.println("FlowInstanceStore: " + flowInstanceStore.getClass().getName());
        System.out.println("======================================================");
    }
}