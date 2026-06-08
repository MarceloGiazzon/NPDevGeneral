package com.npdev.adapters.flowcompiled;

import com.npdev.dsl.v1.compiled.CompiledCapabilityCall;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledCapabilityExecutionPolicy;
import com.npdev.dsl.v1.compiled.CompiledCapabilityOperation;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.FlowDefinition;
import com.npdev.kernel.FlowStepDefinition;
import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.ports.FlowDefinitionProvider;
import com.npdev.kernel.schema.SchemaObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime projection adapter: Compiled DSL flows -> kernel FlowDefinition.
 */
public final class CompiledModelFlowDefinitionProvider implements FlowDefinitionProvider {
    private final Map<String, FlowDefinition> flowsByName = new LinkedHashMap<>();
    private final Map<String, String> adapterIdByCapability = new LinkedHashMap<>();
    private final Map<String, Map<String, CompiledCapabilityOperation>> capabilityOperationsByCapability = new LinkedHashMap<>();

    public CompiledModelFlowDefinitionProvider(CompiledModel compiledModel) {
        if (compiledModel == null) {
            throw new IllegalArgumentException("compiledModel must be non-null");
        }
        for (com.npdev.dsl.v1.compiled.CompiledCapability capability : compiledModel.getCapabilities()) {
            Map<String, CompiledCapabilityOperation> operationsByName = new LinkedHashMap<>();
            for (CompiledCapabilityOperation operation : capability.getOperations()) {
                operationsByName.put(normalize(operation.getName()), operation);
            }
            capabilityOperationsByCapability.put(normalize(capability.getName()), operationsByName);
        }
        for (CompiledCapabilityBinding binding : compiledModel.getBindings()) {
            adapterIdByCapability.put(normalize(binding.getCapability()), binding.getAdapter());
        }
        for (CompiledFlow flow : compiledModel.getFlows()) {
            FlowDefinition definition = toFlowDefinition(flow, adapterIdByCapability, capabilityOperationsByCapability);
            flowsByName.put(normalize(flow.getName()), definition);
        }
    }

    @Override
    public Optional<FlowDefinition> findFlow(String flowName) {
        return Optional.ofNullable(flowsByName.get(normalize(flowName)));
    }

    public FlowDefinition getFlow(String flowName) {
        return findFlow(flowName).orElseThrow(() -> new UnknownFlowException(flowName));
    }

    private static FlowDefinition toFlowDefinition(
            CompiledFlow flow,
            Map<String, String> adapterIdByCapability,
            Map<String, Map<String, CompiledCapabilityOperation>> capabilityOperationsByCapability
    ) {
        List<FlowStepDefinition> steps = toFlowSteps(
                flow.getSteps(),
                flow.getConcept(),
                adapterIdByCapability,
                capabilityOperationsByCapability
        );
        return new FlowDefinition(
                flow.getName(),
                flow.getConcept(),
                steps,
                toSchemaObject(flow.getInputSchema()),
                toSchemaObject(flow.getOutputSchema())
        );
    }

    private static List<FlowStepDefinition> toFlowSteps(
            List<CompiledFlowStep> steps,
            String flowConcept,
            Map<String, String> adapterIdByCapability,
            Map<String, Map<String, CompiledCapabilityOperation>> capabilityOperationsByCapability
    ) {
        List<FlowStepDefinition> out = new ArrayList<>();
        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            CompiledFlowStep step = steps.get(stepIndex);
            String type = normalize(step.getType());
            String name = nonBlank(step.getName(), defaultStepName(type, stepIndex));

            switch (type) {
                case "invariant" -> out.add(FlowStepDefinition.invariant(
                        name,
                        nonBlank(step.getScope(), flowConcept),
                        toCheckpoint(step.getCheckpoint()),
                        step.getInvariants()
                ));
                case "capability", "generatedaction", "createentity", "updateentity", "createconcept", "updateconcept" -> {
                    CompiledCapabilityCall call = step.getCapabilityCall();
                    if (call == null) {
                        throw new IllegalArgumentException("Capability step must contain capability call: " + name);
                    }
                    CapabilityExecutionPolicy policy = toExecutionPolicy(call.getExecutionPolicy());
                    SchemaObject inputSchema = toSchemaObject(call.getInputSchema());
                    SchemaObject outputSchema = toSchemaObject(call.getOutputSchema());
                    CompiledCapabilityOperation operationContract = resolveCapabilityOperation(
                            capabilityOperationsByCapability,
                            call.getCapabilityName(),
                            call.getOperation()
                    );
                    if (operationContract != null) {
                        if (inputSchema == null) {
                            inputSchema = toSchemaObject(operationContract.getInputSchema());
                        }
                        if (outputSchema == null) {
                            outputSchema = toSchemaObject(operationContract.getOutputSchema());
                        }
                    }
                    String capabilityName = call.getCapabilityName();
                    String adapterId = nonBlank(call.getAdapterId(), adapterIdByCapability.get(normalize(capabilityName)));
                    if (adapterId == null || adapterId.isBlank()) {
                        out.add(FlowStepDefinition.capabilityCall(
                                name,
                                capabilityName,
                                call.getCapabilityType(),
                                call.getOperation(),
                                call.getArgsRefs(),
                                call.getOutputRef(),
                                policy,
                                inputSchema,
                                outputSchema
                        ));
                        break;
                    }
                    out.add(FlowStepDefinition.capabilityCall(
                            name,
                            capabilityName,
                            call.getCapabilityType(),
                            adapterId,
                            call.getOperation(),
                            call.getArgsRefs(),
                            call.getOutputRef(),
                            policy,
                            inputSchema,
                            outputSchema
                    ));
                }
                case "event", "emitevent" -> out.add(FlowStepDefinition.emitEvent(
                        name,
                        step.getEventName(),
                        step.getPayloadRef(),
                        step.getEventDataRefs()
                ));
                case "scheduleevent" -> out.add(FlowStepDefinition.scheduleEvent(
                        name,
                        step.getEventName(),
                        step.getPayloadRef(),
                        step.getEventDataRefs(),
                        step.getDelaySeconds() == null ? 0L : step.getDelaySeconds()
                ));
                case "branch" -> out.add(FlowStepDefinition.branch(
                        name,
                        step.getCondition(),
                        toFlowSteps(step.getThenSteps(), flowConcept, adapterIdByCapability, capabilityOperationsByCapability),
                        toFlowSteps(step.getElseSteps(), flowConcept, adapterIdByCapability, capabilityOperationsByCapability)
                ));
                case "map" -> out.add(FlowStepDefinition.map(
                        name,
                        step.getMapFromRef(),
                        step.getMapToRef()
                ));
                case "await" -> out.add(FlowStepDefinition.awaitEvent(
                        name,
                        step.getAwaitEventName(),
                        step.getAwaitRef(),
                        step.getAwaitMatchCorrelation() == null || step.getAwaitMatchCorrelation(),
                        step.getAwaitPayloadMatch()
                ));
                case "return" -> out.add(FlowStepDefinition.returnValue(name, step.getReturnValueRef()));
                default -> throw new IllegalArgumentException(
                        "Unsupported flow step type '" + step.getType() + "' in flow concept " + flowConcept
                );
            }
        }
        return out;
    }

    private static String defaultStepName(String normalizedType, int stepIndex) {
        String base = normalizedType == null || normalizedType.isBlank() ? "step" : normalizedType;
        return base + "-" + stepIndex;
    }

    private static CapabilityExecutionPolicy toExecutionPolicy(CompiledCapabilityExecutionPolicy policy) {
        if (policy == null) {
            return CapabilityExecutionPolicy.defaults();
        }
        CapabilityExecutionPolicy.FailureClassification failureClassification = null;
        String rawClassification = policy.getFailureClassification();
        if (rawClassification != null && !rawClassification.isBlank()) {
            failureClassification = CapabilityExecutionPolicy.FailureClassification.valueOf(
                    rawClassification.trim().toUpperCase(Locale.ROOT)
            );
        }
        return new CapabilityExecutionPolicy(
                Math.max(1, policy.getRetryCount()),
                Math.max(0L, policy.getRetryDelayMs()),
                Math.max(0L, policy.getTimeoutMs()),
                policy.getIdempotencyKeyField(),
                failureClassification
        );
    }

    private static CompiledCapabilityOperation resolveCapabilityOperation(
            Map<String, Map<String, CompiledCapabilityOperation>> capabilityOperationsByCapability,
            String capabilityName,
            String operationName
    ) {
        if (capabilityName == null || operationName == null) {
            return null;
        }
        Map<String, CompiledCapabilityOperation> operations = capabilityOperationsByCapability.get(normalize(capabilityName));
        if (operations == null || operations.isEmpty()) {
            return null;
        }
        return operations.get(normalize(operationName));
    }

    private static FlowStepDefinition.InvariantCheckpoint toCheckpoint(String checkpoint) {
        String value = normalize(checkpoint);
        if (value.isBlank() || "pre".equals(value)) {
            return FlowStepDefinition.InvariantCheckpoint.PRE;
        }
        if ("post".equals(value)) {
            return FlowStepDefinition.InvariantCheckpoint.POST;
        }
        throw new IllegalArgumentException("Unsupported invariant checkpoint: " + checkpoint);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static SchemaObject toSchemaObject(CompiledSchema compiledSchema) {
        if (compiledSchema == null) {
            return null;
        }
        Map<String, SchemaObject> properties = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledSchema> entry : compiledSchema.getProperties().entrySet()) {
            properties.put(entry.getKey(), toSchemaObject(entry.getValue()));
        }
        return new SchemaObject(
                compiledSchema.getType(),
                properties,
                compiledSchema.getRequired(),
                compiledSchema.getDescription(),
                compiledSchema.getMinLength(),
                compiledSchema.getMaxLength(),
                compiledSchema.getMin(),
                compiledSchema.getMax(),
                compiledSchema.getRegex()
        );
    }
}
