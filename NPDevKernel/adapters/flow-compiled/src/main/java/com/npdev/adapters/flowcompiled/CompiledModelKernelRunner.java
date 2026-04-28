package com.npdev.adapters.flowcompiled;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.mvp.ExecutionTrace;
import com.npdev.kernel.mvp.MvpKernelRunner;
import com.npdev.kernel.ports.CapabilityInvoker;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;

import java.util.Objects;

/**
 * Bridge runner that executes a compiled DSL model through the kernel MVP runtime.
 */
public final class CompiledModelKernelRunner {

    private final MvpKernelRunner mvpKernelRunner;

    public CompiledModelKernelRunner(
            EventBus eventBus,
            InvariantEngine invariantEngine,
            CapabilityInvoker capabilityInvoker
    ) {
        this.mvpKernelRunner = new MvpKernelRunner(
                Objects.requireNonNull(eventBus, "eventBus"),
                Objects.requireNonNull(invariantEngine, "invariantEngine"),
                Objects.requireNonNull(capabilityInvoker, "capabilityInvoker")
        );
    }

    public ExecutionTrace run(
            CompiledModel compiledModel,
            String flowName,
            Object input,
            ExecutionContext executionContext
    ) {
        Objects.requireNonNull(compiledModel, "compiledModel");
        CompiledModelFlowDefinitionProvider provider = new CompiledModelFlowDefinitionProvider(compiledModel);
        return mvpKernelRunner.run(provider, flowName, input, executionContext);
    }
}
