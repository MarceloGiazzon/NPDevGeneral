package com.npdev.adapters.flowcompiled;

import com.npdev.dsl.v1.compiled.CompiledCapabilityCall;
import com.npdev.dsl.v1.compiled.CompiledCapabilityBinding;
import com.npdev.dsl.v1.compiled.CompiledCapabilityExecutionPolicy;
import com.npdev.dsl.v1.compiled.CompiledCapabilityOperation;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime projection adapter: Compiled DSL flows -> kernel FlowDefinition.
 *
 * <p>REG-237 (D1 Phase 2b): the constructor's field-population logic does real O(model) work
 * (nested loops over capabilities/bindings/flows plus a full recursive step-tree conversion), so
 * it is built once into an immutable {@link Snapshot} and swapped atomically via {@link #reload}
 * on an actual model hot-reload, rather than rebuilt on every {@link #findFlow} lookup (a hot
 * path called on every {@code KernelRunner.execute}/{@code resumeExecution}). This intentionally
 * does NOT add any flow-shape/version-safety guard for an in-flight {@code WAITING_EVENT}
 * instance resuming across a reshaped flow -- that is tracked separately and out of scope here.
 */
public final class CompiledModelFlowDefinitionProvider implements FlowDefinitionProvider {

    /**
     * Immutable snapshot of everything the constructor used to populate directly. Rebuilt in
     * full by {@link #buildSnapshot(CompiledModel)} and swapped in place by {@link #reload}.
     */
    private record Snapshot(
            Map<String, FlowDefinition> flowsByName,
            Map<String, String> adapterIdByCapability,
            Map<String, Map<String, CompiledCapabilityOperation>> capabilityOperationsByCapability
    ) {
    }

    private final AtomicReference<Snapshot> snapshot;

    public CompiledModelFlowDefinitionProvider(CompiledModel compiledModel) {
        this.snapshot = new AtomicReference<>(buildSnapshot(compiledModel));
    }

    /**
     * REG-237 (D1 Phase 2b): rebuilds the snapshot from a freshly reloaded model and swaps it in
     * atomically, so the next {@link #findFlow} lookup (a fresh {@code execute()} or a
     * {@code resumeExecution()} happening after this call) observes the new flow shapes without
     * reconstructing this provider.
     */
    public void reload(CompiledModel compiledModel) {
        snapshot.set(buildSnapshot(compiledModel));
    }

    private static Snapshot buildSnapshot(CompiledModel compiledModel) {
        if (compiledModel == null) {
            throw new IllegalArgumentException("compiledModel must be non-null");
        }
        Map<String, Map<String, CompiledCapabilityOperation>> capabilityOperationsByCapability = new LinkedHashMap<>();
        for (com.npdev.dsl.v1.compiled.CompiledCapability capability : compiledModel.getCapabilities()) {
            Map<String, CompiledCapabilityOperation> operationsByName = new LinkedHashMap<>();
            for (CompiledCapabilityOperation operation : capability.getOperations()) {
                operationsByName.put(normalize(operation.getName()), operation);
            }
            capabilityOperationsByCapability.put(normalize(capability.getName()), operationsByName);
        }
        Map<String, String> adapterIdByCapability = new LinkedHashMap<>();
        for (CompiledCapabilityBinding binding : compiledModel.getBindings()) {
            adapterIdByCapability.put(normalize(binding.getCapability()), binding.getAdapter());
        }
        ModelFacts facts = ModelFacts.of(compiledModel);
        Map<String, FlowDefinition> flowsByName = new LinkedHashMap<>();
        for (CompiledFlow flow : compiledModel.getFlows()) {
            FlowDefinition definition = toFlowDefinition(
                    flow, adapterIdByCapability, capabilityOperationsByCapability, facts);
            flowsByName.put(normalize(flow.getName()), definition);
        }
        return new Snapshot(flowsByName, adapterIdByCapability, capabilityOperationsByCapability);
    }

    @Override
    public Optional<FlowDefinition> findFlow(String flowName) {
        return Optional.ofNullable(snapshot.get().flowsByName().get(normalize(flowName)));
    }

    public FlowDefinition getFlow(String flowName) {
        return findFlow(flowName).orElseThrow(() -> new UnknownFlowException(flowName));
    }

    private static FlowDefinition toFlowDefinition(
            CompiledFlow flow,
            Map<String, String> adapterIdByCapability,
            Map<String, Map<String, CompiledCapabilityOperation>> capabilityOperationsByCapability,
            ModelFacts facts
    ) {
        List<FlowStepDefinition> steps = toFlowSteps(
                flow.getSteps(),
                flow.getConcept(),
                adapterIdByCapability,
                capabilityOperationsByCapability,
                facts
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
            Map<String, Map<String, CompiledCapabilityOperation>> capabilityOperationsByCapability,
            ModelFacts facts
    ) {
        List<FlowStepDefinition> out = new ArrayList<>();
        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            CompiledFlowStep step = steps.get(stepIndex);
            String type = normalize(step.getType());
            // REG-259: a generatedAction whose actionName is a procedure declared in this model (and
            // not a packaged trusted-source one) has no generated-action capability behind it -- only
            // trusted-source procedures register one -- so it ran into "capability binding not found"
            // AFTER the preceding steps had already written. Run the declared procedure instead,
            // exactly as a callProcedure step would.
            String inProcessAction = "generatedaction".equals(type)
                    ? facts.inProcessProcedureName(step.getGeneratedActionName())
                    : null;
            if (inProcessAction != null) {
                type = "callprocedure";
            }
            String name = nonBlank(step.getName(), defaultStepName(type, stepIndex));
            int builtSizeBefore = out.size();

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
                        toFlowSteps(step.getThenSteps(), flowConcept, adapterIdByCapability, capabilityOperationsByCapability, facts),
                        toFlowSteps(step.getElseSteps(), flowConcept, adapterIdByCapability, capabilityOperationsByCapability, facts)
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
                case "foreach" -> out.add(FlowStepDefinition.forEach(
                        name,
                        step.getCollectionRef(),
                        step.getItemKey(),
                        toFlowSteps(step.getLoopSteps(), flowConcept, adapterIdByCapability, capabilityOperationsByCapability, facts),
                        step.getMaxLoopIterations(),
                        // B15(B) (S6, docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)): null/absent means
                        // sequential (B15(A)'s default), matching FlowStepDefinition's own default.
                        Boolean.TRUE.equals(step.getParallelAwait())
                ));
                // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): step.getMapFromRef()/
                // getMapToRef() carry this step's own "input"/"output" JSON properties here --
                // ModelCompiler.compileFlowSteps populates those two slots from stepAst.getInput()/
                // getOutput() for EVERY flow step type, not just "map" (confirmed by reading that
                // method directly), so reusing them is consistent with how every other step already
                // gets its input/output threaded through, not a repurposing specific to this step.
                case "callprocedure" -> out.add(FlowStepDefinition.callProcedure(
                        name,
                        inProcessAction != null ? inProcessAction : step.getProcedureName(),
                        inProcessAction != null ? firstArgRef(step.getCapabilityCall()) : step.getMapFromRef(),
                        inProcessAction != null && step.getCapabilityCall() != null
                                ? step.getCapabilityCall().getOutputRef()
                                : step.getMapToRef()
                ));
                default -> throw new IllegalArgumentException(
                        "Unsupported flow step type '" + step.getType() + "' in flow concept " + flowConcept
                );
            }

            // REG-262/263/264: createConcept/updateConcept both compile to the same bare
            // persistence.save call; record which one the author wrote so the kernel can enforce the
            // difference (required fields on create; row must exist, merge into it, on update).
            FlowStepDefinition.ConceptWrite conceptWrite = conceptWriteOf(type, flowConcept, facts);
            if (conceptWrite != null) {
                out.set(builtSizeBefore, out.get(builtSizeBefore).withConceptWrite(conceptWrite));
            }

            // LNCH-17: every case above adds exactly one built step -- attach its declared
            // compensation steps (if any) onto that just-built step rather than threading
            // onFailureSteps through every FlowStepDefinition factory method individually.
            if (!step.getOnFailureSteps().isEmpty()) {
                List<FlowStepDefinition> onFailureSteps = toFlowSteps(
                        step.getOnFailureSteps(), flowConcept, adapterIdByCapability, capabilityOperationsByCapability, facts
                );
                out.set(builtSizeBefore, out.get(builtSizeBefore).withOnFailure(onFailureSteps));
            }

            // R2.5: attach a durable await deadline + timeout-branch steps onto an "await" step the
            // same way LNCH-17 attaches onFailureSteps above -- only AWAIT_EVENT ever sets this.
            if ("await".equals(type) && step.getTimeoutSeconds() != null) {
                List<FlowStepDefinition> onTimeoutSteps = toFlowSteps(
                        step.getOnTimeoutSteps(), flowConcept, adapterIdByCapability, capabilityOperationsByCapability, facts
                );
                out.set(builtSizeBefore, out.get(builtSizeBefore).withTimeout(step.getTimeoutSeconds(), onTimeoutSteps));
            }
        }
        return out;
    }

    /** The generatedAction step's input ref: it travels as the capability call's first argument. */
    private static String firstArgRef(CompiledCapabilityCall call) {
        if (call == null || call.getArgsRefs() == null || call.getArgsRefs().isEmpty()) {
            return null;
        }
        return call.getArgsRefs().get(0);
    }

    /** What the provider needs to know about the whole model to compile one step. */
    private record ModelFacts(
            Map<String, List<String>> requiredRefsByConcept,
            Map<String, String> inProcessProcedureByAction
    ) {
        static ModelFacts of(CompiledModel compiledModel) {
            Map<String, String> byAction = new LinkedHashMap<>();
            for (CompiledProcedure procedure : compiledModel.getProcedures()) {
                Object entrypoint = procedure.metadata().get("untrustedExtensionEntrypoint");
                if (entrypoint != null && !String.valueOf(entrypoint).isBlank()) {
                    continue; // packaged trusted-source procedure: the generated-action capability runs it
                }
                byAction.putIfAbsent(normalize(procedure.name()), procedure.name());
                if (procedure.actionDescriptor() != null && hasText(procedure.actionDescriptor().actionName())) {
                    byAction.putIfAbsent(normalize(procedure.actionDescriptor().actionName()), procedure.name());
                }
            }
            return new ModelFacts(computeRequiredRefsByConcept(compiledModel), byAction);
        }

        /** The declared (non-trusted) procedure a generatedAction named {@code actionName} resolves to, or null. */
        String inProcessProcedureName(String actionName) {
            return actionName == null ? null : inProcessProcedureByAction.get(normalize(actionName));
        }
    }

    private static FlowStepDefinition.ConceptWrite conceptWriteOf(
            String normalizedType,
            String flowConcept,
            ModelFacts facts
    ) {
        return switch (normalizedType) {
            case "createconcept", "createentity" -> new FlowStepDefinition.ConceptWrite(
                    FlowStepDefinition.ConceptWrite.CREATE,
                    facts.requiredRefsByConcept().getOrDefault(normalize(flowConcept), List.of()));
            case "updateconcept", "updateentity" -> new FlowStepDefinition.ConceptWrite(
                    FlowStepDefinition.ConceptWrite.UPDATE, List.of());
            default -> null;
        };
    }

    /**
     * Per concept, the {@code required(<field>)} refs a flow create must satisfy ITSELF: required
     * fields that the persistence layer's create-time defaulting pass will not fill. A required field
     * with a default, a defaultExpression, a derivedExpression, or that is the lifecycle status field
     * is left out -- the adapter supplies those, exactly as the REST create materialises them before it
     * validates, so checking them up front would refuse rows the REST create accepts. The id field is
     * left out too (the adapter mints one). The ref spelling is the one ModelCompiler registers.
     */
    private static Map<String, List<String>> computeRequiredRefsByConcept(CompiledModel compiledModel) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (CompiledConcept concept : compiledModel.getConcepts()) {
            String statusField = concept.getLifecycle() == null ? null : concept.getLifecycle().getStatusField();
            List<String> refs = new ArrayList<>();
            for (CompiledField field : concept.getFields()) {
                if (!field.isRequired() || field.isId()) {
                    continue;
                }
                CompiledSchema schema = field.getSchema();
                boolean supplied = schema != null
                        && (schema.getDefaultValue() != null
                        || hasText(schema.getDefaultExpression())
                        || hasText(schema.getDerivedExpression()));
                boolean lifecycleStatus = statusField != null && statusField.equals(field.getName());
                if (!supplied && !lifecycleStatus) {
                    refs.add("required(" + field.getName() + ")");
                }
            }
            out.put(normalize(concept.getName()), List.copyOf(refs));
        }
        return out;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String defaultStepName(String normalizedType, int stepIndex) {
        String base = normalizedType == null || normalizedType.isBlank() ? "step" : normalizedType;
        return base + "-" + stepIndex;
    }

    /**
     * The one DSL-policy -> kernel-policy converter. Public so the procedure side (RUN-1,
     * {@code ProcedureRunner}) resolves a capability operation's declared policy exactly as a flow
     * step does, instead of keeping a second copy that could drift.
     */
    public static CapabilityExecutionPolicy toExecutionPolicy(CompiledCapabilityExecutionPolicy policy) {
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
                // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5 / capabilityPolicy): previously
                // dropped here entirely -- a declared circuitOpenAfterFailures/circuitOpenMs/
                // bulkheadMaxConcurrent silently did nothing, every capability instead always using
                // KernelRunner's hardcoded CIRCUIT_FAILURE_THRESHOLD/CIRCUIT_OPEN_DURATION_MS/
                // BULKHEAD_MAX_CONCURRENT constants regardless of what the model declared.
                Math.max(0, policy.getCircuitOpenAfterFailures()),
                Math.max(0L, policy.getCircuitOpenMs()),
                Math.max(0, policy.getBulkheadMaxConcurrent()),
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
