package com.npdev.adapters.flowcompiled;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.adapters.flowcompiled.CompiledModelEventSchemaProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Composition-root utility:
 * model.json -> parse/validate/compile -> kernel runner with compiled flow provider.
 */
public final class ModelBackedKernelRuntimeFactory {
    private ModelBackedKernelRuntimeFactory() {
    }

    public static CompiledModel compileModel(Path modelPath) {
        Objects.requireNonNull(modelPath, "modelPath");

        try {
            ModelAst modelAst = new JsonModelParser().parse(modelPath);
            List<String> errors = new SemanticValidator().validate(modelAst);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Model validation failed: " + String.join(" | ", errors));
            }
            return new ModelCompiler().compile(modelAst);
        } catch (IOException ioException) {
            throw new IllegalArgumentException("Unable to load model file: " + modelPath, ioException);
        }
    }

    public static KernelRunner createKernelRunner(
            CompiledModel compiledModel,
            EventBus eventBus,
            InvariantEngine invariantEngine,
            CapabilityDispatcher capabilityDispatcher
    ) {
        return createKernelRunner(
                compiledModel,
                eventBus,
                invariantEngine,
                capabilityDispatcher,
                ExecutionTracer.NOOP
        );
    }

    public static KernelRunner createKernelRunner(
            CompiledModel compiledModel,
            EventBus eventBus,
            InvariantEngine invariantEngine,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer
    ) {
        Objects.requireNonNull(compiledModel, "compiledModel");
        Objects.requireNonNull(eventBus, "eventBus");
        Objects.requireNonNull(invariantEngine, "invariantEngine");
        Objects.requireNonNull(capabilityDispatcher, "capabilityDispatcher");

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                new CompiledModelFlowDefinitionProvider(compiledModel),
                capabilityDispatcher,
                executionTracer
        );
        runner.withEventSchemaProvider(new CompiledModelEventSchemaProvider(compiledModel));
        return runner;
    }

    public static KernelRunner createKernelRunner(
            CompiledModel compiledModel,
            EventBus eventBus,
            EventStore eventStore,
            InvariantEngine invariantEngine,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer,
            FlowInstanceStore flowInstanceStore
    ) {
        Objects.requireNonNull(compiledModel, "compiledModel");
        Objects.requireNonNull(eventBus, "eventBus");
        Objects.requireNonNull(eventStore, "eventStore");
        Objects.requireNonNull(invariantEngine, "invariantEngine");
        Objects.requireNonNull(capabilityDispatcher, "capabilityDispatcher");

        KernelRunner runner = new KernelRunner(
                eventBus,
                invariantEngine,
                new CompiledModelFlowDefinitionProvider(compiledModel),
                capabilityDispatcher,
                executionTracer,
                eventStore,
                flowInstanceStore
        );
        runner.withEventSchemaProvider(new CompiledModelEventSchemaProvider(compiledModel));
        return runner;
    }

    public static KernelRunner createKernelRunner(
            Path modelPath,
            EventBus eventBus,
            InvariantEngine invariantEngine,
            CapabilityDispatcher capabilityDispatcher
    ) {
        return createKernelRunner(compileModel(modelPath), eventBus, invariantEngine, capabilityDispatcher);
    }

    public static KernelRunner createKernelRunner(
            Path modelPath,
            EventBus eventBus,
            InvariantEngine invariantEngine,
            CapabilityDispatcher capabilityDispatcher,
            ExecutionTracer executionTracer
    ) {
        return createKernelRunner(compileModel(modelPath), eventBus, invariantEngine, capabilityDispatcher, executionTracer);
    }
}
