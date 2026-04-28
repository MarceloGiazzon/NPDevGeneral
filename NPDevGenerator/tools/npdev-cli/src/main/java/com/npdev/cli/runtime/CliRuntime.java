package com.npdev.cli.runtime;

import com.npdev.adapters.circuit.inproc.InProcCircuitBreakerStateStore;
import com.npdev.adapters.events.inproc.InProcEventStore;
import com.npdev.adapters.flowinstance.inproc.InProcCorrelationOwnershipStore;
import com.npdev.adapters.flowinstance.inproc.InProcFlowInstanceStore;
import com.npdev.adapters.idempotency.inproc.InProcIdempotencyStore;
import com.npdev.adapters.tracing.inproc.InProcExecutionTracer;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.ports.CapabilityDispatcher;

public record CliRuntime(
        CompiledModel compiledModel,
        KernelRunner kernelRunner,
        CapabilityDispatcher capabilityDispatcher,
        ConceptGateway conceptGateway,
        InProcEventStore eventStore,
        InProcFlowInstanceStore flowInstanceStore,
        InProcExecutionTracer traceStore,
        InProcCorrelationOwnershipStore correlationOwnershipStore,
        InProcCircuitBreakerStateStore circuitBreakerStateStore,
        InProcIdempotencyStore idempotencyStore
) {
    public CliRuntime(
            CompiledModel compiledModel,
            KernelRunner kernelRunner,
            InProcEventStore eventStore,
            InProcFlowInstanceStore flowInstanceStore,
            InProcExecutionTracer traceStore,
            InProcCorrelationOwnershipStore correlationOwnershipStore,
            InProcCircuitBreakerStateStore circuitBreakerStateStore,
            InProcIdempotencyStore idempotencyStore
    ) {
        this(
                compiledModel,
                kernelRunner,
                null,
                null,
                eventStore,
                flowInstanceStore,
                traceStore,
                correlationOwnershipStore,
                circuitBreakerStateStore,
                idempotencyStore
        );
    }
}
