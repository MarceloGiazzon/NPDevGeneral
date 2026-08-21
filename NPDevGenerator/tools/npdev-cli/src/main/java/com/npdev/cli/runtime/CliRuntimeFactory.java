package com.npdev.cli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.adapters.bulkhead.inproc.InProcBulkheadStore;
import com.npdev.adapters.circuit.inproc.InProcCircuitBreakerStateStore;
import com.npdev.adapters.events.inproc.InProcEventBus;
import com.npdev.adapters.events.inproc.InProcEventStore;
import com.npdev.runtime.support.CelInvariantEngine;
import com.npdev.adapters.flowcompiled.CompiledModelFlowDefinitionProvider;
import com.npdev.adapters.flowcompiled.ModelBackedKernelRuntimeFactory;
import com.npdev.adapters.flowcompiled.CompiledModelEventSchemaProvider;
import com.npdev.adapters.flowinstance.inproc.InProcCorrelationOwnershipStore;
import com.npdev.adapters.flowinstance.inproc.InProcFlowInstanceStore;
import com.npdev.adapters.idempotency.inproc.InProcIdempotencyStore;
import com.npdev.adapters.json.jackson.JacksonJsonCodec;
import com.npdev.adapters.schema.validator.DefaultSchemaValidator;
import com.npdev.adapters.tracing.inproc.InProcExecutionTracer;
import com.npdev.cli.sim.MockCapabilityDispatcher;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.security.PermissionGrant;
import com.npdev.kernel.security.StaticPermissionEvaluator;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CliRuntimeFactory {
    private static final String STATE_FILE = "cli-state.json";

    private final ObjectMapper objectMapper;

    public CliRuntimeFactory() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public CliRuntimeFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public CliRuntime create(CliRuntimeOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.modelPath() == null) {
            throw new IllegalArgumentException("--model is required");
        }
        CompiledModel compiledModel = ModelBackedKernelRuntimeFactory.compileModel(options.modelPath());
        MockCapabilityDispatcher capabilityDispatcher = options.simulationPath() == null
                ? MockCapabilityDispatcher.defaults()
                : MockCapabilityDispatcher.fromFile(options.simulationPath(), objectMapper);

        InProcEventBus eventBus = new InProcEventBus();
        InProcEventStore eventStore = new InProcEventStore();
        InProcFlowInstanceStore flowInstanceStore = new InProcFlowInstanceStore();
        InProcExecutionTracer traceStore = new InProcExecutionTracer();
        InProcCorrelationOwnershipStore correlationOwnershipStore = new InProcCorrelationOwnershipStore();
        InProcCircuitBreakerStateStore circuitStore = new InProcCircuitBreakerStateStore();
        InProcBulkheadStore bulkheadStore = new InProcBulkheadStore();
        InProcIdempotencyStore idempotencyStore = new InProcIdempotencyStore();
        JacksonJsonCodec jsonCodec = new JacksonJsonCodec(objectMapper);
        DefaultSchemaValidator schemaValidator = new DefaultSchemaValidator();
        StaticPermissionEvaluator permissionEvaluator = new StaticPermissionEvaluator(loadPermissionGrants(options));

        if (options.storeDir() != null) {
            hydrateState(
                    options.storeDir(),
                    eventStore,
                    flowInstanceStore,
                    traceStore,
                    correlationOwnershipStore,
                    circuitStore,
                    idempotencyStore
            );
        }

        KernelRunner kernelRunner = new KernelRunner(
                eventBus,
                CelInvariantEngine.fromCompiledModel(compiledModel),
                new CompiledModelFlowDefinitionProvider(compiledModel),
                capabilityDispatcher,
                traceStore,
                eventStore,
                flowInstanceStore,
                correlationOwnershipStore,
                circuitStore,
                bulkheadStore,
                idempotencyStore,
                jsonCodec,
                schemaValidator
        );

        kernelRunner.withEventSchemaProvider(new CompiledModelEventSchemaProvider(compiledModel));
        kernelRunner.withPermissionEvaluator(permissionEvaluator);
        eventStore.registerAppendListener(kernelRunner::onEventPersisted);
        ConceptGateway conceptGateway = new DefaultConceptGateway(new InMemoryConceptStore());

        return new CliRuntime(
                compiledModel,
                kernelRunner,
                capabilityDispatcher,
                conceptGateway,
                eventStore,
                flowInstanceStore,
                traceStore,
                correlationOwnershipStore,
                circuitStore,
                idempotencyStore
        );
    }

    private List<PermissionGrant> loadPermissionGrants(CliRuntimeOptions options) {
        Path permissionManifestPath = options.permissionManifestPath();
        if (permissionManifestPath == null) {
            permissionManifestPath = Path.of("resources", "security", "dev.permissions.json");
        }
        if (!Files.exists(permissionManifestPath)) {
            return List.of();
        }
        try {
            return new PermissionManifestLoader(objectMapper).load(permissionManifestPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed loading permission manifest from " + permissionManifestPath, exception);
        }
    }

    public void persist(CliRuntime runtime, Path storeDir) {
        Objects.requireNonNull(runtime, "runtime");
        if (storeDir == null) {
            return;
        }
        try {
            Files.createDirectories(storeDir);
            Path stateFile = storeDir.resolve(STATE_FILE);
            CliPersistedState state = new CliPersistedState(
                    runtime.eventStore().snapshotEvents(),
                    runtime.flowInstanceStore().snapshotInstances(),
                    runtime.traceStore().snapshotTraces(),
                    runtime.correlationOwnershipStore().snapshotOwners(),
                    runtime.idempotencyStore().snapshotRecords(),
                    serializeCircuitStates(runtime.circuitBreakerStateStore().snapshotStates())
            );
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), state);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed persisting CLI state into " + storeDir, exception);
        }
    }

    private void hydrateState(
            Path storeDir,
            InProcEventStore eventStore,
            InProcFlowInstanceStore flowInstanceStore,
            InProcExecutionTracer traceStore,
            InProcCorrelationOwnershipStore correlationOwnershipStore,
            InProcCircuitBreakerStateStore circuitStore,
            InProcIdempotencyStore idempotencyStore
    ) {
        Path stateFile = storeDir.resolve(STATE_FILE);
        if (!Files.exists(stateFile)) {
            return;
        }
        try {
            CliPersistedState persistedState = objectMapper.readValue(stateFile.toFile(), CliPersistedState.class);
            if (persistedState == null) {
                return;
            }
            loadEvents(persistedState.events(), eventStore);
            loadFlowInstances(persistedState.flowInstances(), flowInstanceStore);
            loadTraces(persistedState.traces(), traceStore);
            loadCorrelationOwners(persistedState.correlationOwners(), correlationOwnershipStore);
            loadIdempotency(persistedState.idempotencyRecords(), idempotencyStore);
            loadCircuitStates(persistedState.circuitStates(), circuitStore);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed loading CLI state from " + stateFile, exception);
        }
    }

    private static void loadEvents(List<EventEnvelope> events, InProcEventStore eventStore) {
        if (events == null) {
            return;
        }
        for (EventEnvelope event : events) {
            if (event != null) {
                eventStore.append(event);
            }
        }
    }

    private static void loadFlowInstances(List<FlowInstance> flowInstances, InProcFlowInstanceStore flowInstanceStore) {
        if (flowInstances == null) {
            return;
        }
        for (FlowInstance instance : flowInstances) {
            if (instance != null) {
                flowInstanceStore.save(instance);
            }
        }
    }

    private static void loadTraces(List<FlowTrace> traces, InProcExecutionTracer traceStore) {
        if (traces == null) {
            return;
        }
        for (FlowTrace trace : traces) {
            if (trace != null) {
                traceStore.save(trace);
            }
        }
    }

    private static void loadCorrelationOwners(
            Map<String, String> owners,
            InProcCorrelationOwnershipStore correlationOwnershipStore
    ) {
        if (owners == null) {
            return;
        }
        for (Map.Entry<String, String> owner : owners.entrySet()) {
            if (owner.getKey() != null && owner.getValue() != null) {
                correlationOwnershipStore.claimCorrelation(owner.getKey(), owner.getValue());
            }
        }
    }

    private static void loadIdempotency(List<IdempotencyRecord> records, InProcIdempotencyStore idempotencyStore) {
        if (records == null) {
            return;
        }
        for (IdempotencyRecord record : records) {
            if (record == null) {
                continue;
            }
            if (IdempotencyRecord.STATUS_SUCCESS.equals(record.status())) {
                idempotencyStore.saveSuccess(
                        record.tenantId(),
                        record.capabilityName(),
                        record.operationName(),
                        record.idempotencyKey(),
                        record.resultJsonRedacted(),
                        record.createdAtMs()
                );
                continue;
            }
            idempotencyStore.saveFailure(
                    record.tenantId(),
                    record.capabilityName(),
                    record.operationName(),
                    record.idempotencyKey(),
                    record.errorCode(),
                    record.createdAtMs()
            );
        }
    }

    private static void loadCircuitStates(
            Map<String, CircuitBreakerState> states,
            InProcCircuitBreakerStateStore circuitStore
    ) {
        if (states == null) {
            return;
        }
        for (Map.Entry<String, CircuitBreakerState> entry : states.entrySet()) {
            CapabilityOpKey key = parseCapabilityOpKey(entry.getKey());
            if (key != null && entry.getValue() != null) {
                circuitStore.put(key, entry.getValue());
            }
        }
    }

    private static Map<String, CircuitBreakerState> serializeCircuitStates(Map<CapabilityOpKey, CircuitBreakerState> states) {
        if (states == null || states.isEmpty()) {
            return Map.of();
        }
        Map<String, CircuitBreakerState> out = new LinkedHashMap<>();
        for (Map.Entry<CapabilityOpKey, CircuitBreakerState> entry : states.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            out.put(
                    entry.getKey().tenantId() + "|" + entry.getKey().capabilityName() + "|" + entry.getKey().operationName(),
                    entry.getValue()
            );
        }
        return Map.copyOf(out);
    }

    private static CapabilityOpKey parseCapabilityOpKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split("\\|", 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new CapabilityOpKey(parts[0], parts[1], parts[2]);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
