package com.npdev.kernel;

import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.security.PermissionDecision;
import com.npdev.kernel.security.PermissionRequirement;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code CAPABILITY_CALL} step-kind case body, split out of {@link KernelRunner#executeSteps}'s
 * switch verbatim -- no behavior change. See {@link InvariantCheckStep} for the shared
 * {@code execute} return convention. Stays a flat sibling of {@link KernelRunner} in {@code
 * com.npdev.kernel}, not a subpackage, for the same reason the rest of this split's files do: the
 * collaborators (evaluatePermission, validateInput, resolveEffectiveCapabilityPolicy,
 * invokeCapabilityWithPolicy, traceFailedStep) are package-private.
 */
final class CapabilityCallStep {

    private CapabilityCallStep() {
    }

    static KernelRunner.StepExecutionOutcome execute(KernelRunner runner, StepExecutionRequest req) {
        FlowDefinition flow = req.flow();
        FlowStepDefinition step = req.step();
        Object input = req.input();
        Map<String, Object> state = req.state();
        List<EventEnvelope> emittedEvents = req.emittedEvents();
        FlowTraceMeta traceMeta = req.traceMeta();
        List<StepTrace> stepTraces = req.stepTraces();
        String executionId = req.executionId();
        String defaultCorrelationId = req.defaultCorrelationId();
        int traceStepIndex = req.traceStepIndex();
        long stepStartedAt = req.stepStartedAt();
        Map<String, Object> stateBefore = req.stateBefore();
        Map<String, Object> stepInfo = req.stepInfo();
        ExecutionContext effectiveContext = req.effectiveContext();

        List<Object> args = KernelRunner.resolveArgs(step, state, input);

        PermissionDecision capabilityDecision = runner.evaluatePermission(
                effectiveContext,
                new PermissionRequirement("capability.invoke", "capability", step.getCapability())
        );
        if (!capabilityDecision.allowed()) {
            CapabilityError capabilityError = new CapabilityError(
                    "CAPABILITY_PERMISSION_DENIED",
                    capabilityDecision.message().isBlank()
                            ? "Capability invocation denied"
                            : capabilityDecision.message(),
                    CapabilityErrorKind.AUTH,
                    Map.of(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "adapterId", step.getCapabilityAdapterId(),
                            "permissionCode", capabilityDecision.code()
                    )
            );
            runner.traceFailedStep(
                    traceMeta,
                    step,
                    traceStepIndex,
                    stepStartedAt,
                    stateBefore,
                    state,
                    stepInfo,
                    List.of(),
                    capabilityError,
                    stepTraces
            );
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.capabilityFailed(
                    flow.getName(),
                    emittedEvents,
                    step.getName(),
                    traceStepIndex,
                    step.getCapability(),
                    step.getOperation(),
                    step.getCapabilityAdapterId(),
                    capabilityError,
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }

        // Capability contract validation: input shape must be validated before dispatch.
        List<InputValidationError> capabilityInputErrors = runner.validateInput(step.getCapabilityInputSchema(),
                args == null || args.isEmpty() ? null : args.get(0));
        if (!capabilityInputErrors.isEmpty()) {
            CapabilityError capabilityError = new CapabilityError(
                    "CAPABILITY_CONTRACT_INPUT_INVALID",
                    "Capability input validation failed",
                    CapabilityErrorKind.CONTRACT,
                    Map.of(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "adapterId", step.getCapabilityAdapterId(),
                            "validationErrors", capabilityInputErrors
                    )
            );
            runner.traceFailedStep(
                    traceMeta,
                    step,
                    traceStepIndex,
                    stepStartedAt,
                    stateBefore,
                    state,
                    stepInfo,
                    List.of(),
                    capabilityError,
                    stepTraces
            );
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.capabilityFailed(
                    flow.getName(),
                    emittedEvents,
                    step.getName(),
                    traceStepIndex,
                    step.getCapability(),
                    step.getOperation(),
                    step.getCapabilityAdapterId(),
                    capabilityError,
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }

        // REG-262: a createConcept step must satisfy the concept's required-field invariants exactly
        // as the REST create does -- the persistence adapter only fills defaults, it never refuses a
        // row for a missing required field, so without this a flow stored nulls in required columns.
        // Same engine, same required(<field>) refs and the same INVARIANT_FAILED outcome an
        // invariantCheck step produces, so a caller sees one failure shape for one kind of failure.
        FlowStepDefinition.ConceptWrite conceptWrite = step.getConceptWrite();
        if (conceptWrite != null
                && conceptWrite.isCreate()
                && !conceptWrite.requiredInvariantRefs().isEmpty()
                && args != null
                && !args.isEmpty()
                && args.get(0) instanceof Map<?, ?>) {
            String conceptName = KernelRunner.resolveInvariantConceptName(step, flow);
            List<String> invariantRefs = conceptWrite.requiredInvariantRefs();
            InvariantEngine.InvariantEvaluationResult evalResult = runner.invariantEngine.evaluate(
                    new InvariantEngine.InvariantEvaluationRequest(
                            conceptName,
                            args.get(0),
                            invariantRefs,
                            new InvariantEngine.EvaluationMetadata(
                                    flow.getName(),
                                    step.getName(),
                                    traceStepIndex,
                                    FlowStepDefinition.InvariantCheckpoint.PRE,
                                    Objects.toString(state.get("correlationId"), defaultCorrelationId)
                            ),
                            state
                    )
            );
            List<InvariantEngine.Violation> violations = KernelRunner.enrichInvariantViolations(
                    evalResult.violations(),
                    conceptName,
                    flow.getName(),
                    step.getName(),
                    traceStepIndex,
                    invariantRefs
            );
            if (!violations.isEmpty()) {
                runner.traceFailedStep(
                        traceMeta,
                        step,
                        traceStepIndex,
                        stepStartedAt,
                        stateBefore,
                        state,
                        stepInfo,
                        violations,
                        null,
                        stepTraces
                );
                return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.invariantFailed(
                        flow.getName(),
                        violations,
                        emittedEvents,
                        "Required-field check failed at step: " + step.getName(),
                        executionId,
                        Objects.toString(state.get("correlationId"), defaultCorrelationId),
                        executionId
                ));
            }
        }

        // REG-338 / REG-330: a createConcept / updateConcept step must pass the concept's row-level and
        // field-level write rules as the flow's actor, exactly as the REST write does -- the adapter
        // below never consults them.
        if (conceptWrite != null && runner.conceptGateway() != null) {
            CapabilityError rowScopeDenied = rowWriteDenial(runner, flow, step, args, state, effectiveContext);
            if (rowScopeDenied != null) {
                runner.traceFailedStep(
                        traceMeta,
                        step,
                        traceStepIndex,
                        stepStartedAt,
                        stateBefore,
                        state,
                        stepInfo,
                        List.of(),
                        rowScopeDenied,
                        stepTraces
                );
                return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.capabilityFailed(
                        flow.getName(),
                        emittedEvents,
                        step.getName(),
                        traceStepIndex,
                        step.getCapability(),
                        step.getOperation(),
                        step.getCapabilityAdapterId(),
                        rowScopeDenied,
                        executionId,
                        Objects.toString(state.get("correlationId"), defaultCorrelationId),
                        executionId
                ));
            }
        }

        String correlationId = Objects.toString(state.get("correlationId"), null);
        stepInfo.put("capability", step.getCapability());
        stepInfo.put("operation", step.getOperation());
        stepInfo.put("adapterId", step.getCapabilityAdapterId());
        CapabilityExecutionPolicy policy = step.getCapabilityExecutionPolicy() == null
                ? CapabilityExecutionPolicy.defaults()
                : step.getCapabilityExecutionPolicy();
        KernelRunner.EffectiveCapabilityPolicy effectivePolicy = runner.resolveEffectiveCapabilityPolicy(
                step,
                policy,
                KernelRunner.normalizeTenantOrDefault(effectiveContext.tenantId())
        );
        stepInfo.put("retryCount", effectivePolicy.retryMaxAttempts());
        stepInfo.put("retryDelayMs", effectivePolicy.retryBaseDelayMs());
        stepInfo.put("retryMaxDelayMs", effectivePolicy.retryMaxDelayMs());
        stepInfo.put("timeoutMs", effectivePolicy.timeoutMs());
        stepInfo.put("circuitOpenAfterFailures", effectivePolicy.circuitOpenAfterFailures());
        stepInfo.put("circuitOpenMs", effectivePolicy.circuitOpenMs());
        stepInfo.put("bulkheadMaxConcurrent", effectivePolicy.bulkheadMaxConcurrent());
        stepInfo.put("cacheIdempotencyFailures", effectivePolicy.cacheIdempotencyFailures());
        stepInfo.put("failureClassification",
                policy.failureClassification() == null ? null : policy.failureClassification().name());

        String idempotencyKey = KernelRunner.resolveIdempotencyKey(policy, state, input);
        if (idempotencyKey != null) {
            stepInfo.put("idempotencyKey", idempotencyKey);
        }

        CapabilityResult capabilityResult = runner.invokeCapabilityWithPolicy(
                step,
                args,
                state,
                correlationId,
                idempotencyKey,
                policy,
                effectivePolicy,
                stepInfo,
                KernelRunner.normalizeTenantOrDefault(effectiveContext.tenantId())
        );
        if (!capabilityResult.ok()) {
            CapabilityError capabilityError = capabilityResult.error();
            if (capabilityError == null) {
                capabilityError = new CapabilityError(
                        "CAPABILITY_RESULT_INVALID",
                        "Capability dispatcher returned failed result without error",
                        CapabilityErrorKind.PERMANENT,
                        Map.of(
                                "capability", step.getCapability(),
                                "operation", step.getOperation(),
                                "adapterId", step.getCapabilityAdapterId()
                        )
                );
            } else {
                CapabilityErrorKind overriddenKind = policy.applyFailureClassification(capabilityError.kind());
                if (overriddenKind != capabilityError.kind()) {
                    capabilityError = new CapabilityError(
                            capabilityError.code(),
                            capabilityError.message(),
                            overriddenKind,
                            capabilityError.details()
                    );
                }
            }
            // A database integrity violation (a bond/FK to a missing row, or a unique
            // conflict) is a caller CONTRACT failure, not a system error. The persistence
            // adapter surfaces it with a stable message signature; recover the CONTRACT
            // classification here in case an intermediate sandbox/dispatch wrapper widened
            // it to a generic kind (which would otherwise report system_exception).
            capabilityError = KernelRunner.reclassifyIntegrityViolation(capabilityError);
            runner.traceFailedStep(
                    traceMeta,
                    step,
                    traceStepIndex,
                    stepStartedAt,
                    stateBefore,
                    state,
                    stepInfo,
                    List.of(),
                    capabilityError,
                    stepTraces
            );
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.capabilityFailed(
                    flow.getName(),
                    emittedEvents,
                    step.getName(),
                    traceStepIndex,
                    step.getCapability(),
                    step.getOperation(),
                    step.getCapabilityAdapterId(),
                    capabilityError,
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }
        Object output = capabilityResult.value();

        // Capability contract validation: output shape must be validated after dispatch.
        List<InputValidationError> capabilityOutputErrors = runner.validateInput(step.getCapabilityOutputSchema(), output);
        if (!capabilityOutputErrors.isEmpty()) {
            CapabilityError capabilityError = new CapabilityError(
                    "CAPABILITY_CONTRACT_OUTPUT_INVALID",
                    "Capability output validation failed",
                    CapabilityErrorKind.CONTRACT,
                    Map.of(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "adapterId", step.getCapabilityAdapterId(),
                            "validationErrors", capabilityOutputErrors
                    )
            );
            runner.traceFailedStep(
                    traceMeta,
                    step,
                    traceStepIndex,
                    stepStartedAt,
                    stateBefore,
                    state,
                    stepInfo,
                    List.of(),
                    capabilityError,
                    stepTraces
            );
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.capabilityFailed(
                    flow.getName(),
                    emittedEvents,
                    step.getName(),
                    traceStepIndex,
                    step.getCapability(),
                    step.getOperation(),
                    step.getCapabilityAdapterId(),
                    capabilityError,
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }


        String outputRef = KernelRunner.normalizeRef(step.getOutputRef());
        if (!outputRef.isBlank()) {
            state.put(outputRef, output);
        }
        state.put("last", output);
        return null;
    }

    /**
     * REG-338 / REG-330: runs the concept's row-level and field-level write rules for this createConcept / updateConcept step
     * as the flow's actor (see {@link FlowActor}) and returns the AUTH failure to report, or
     * {@code null} when the write may proceed. The check is skipped where there is no trustworthy
     * actor to evaluate (a platform principal, or an instance that predates the actor snapshot) and
     * when the payload is not an object (nothing to scope-check; the step's own input validation
     * reports it).
     */
    private static CapabilityError rowWriteDenial(
            KernelRunner runner,
            FlowDefinition flow,
            FlowStepDefinition step,
            List<Object> args,
            Map<String, Object> state,
            ExecutionContext effectiveContext
    ) {
        if (args == null || args.isEmpty() || !(args.get(0) instanceof Map<?, ?> payload)) {
            return null;
        }
        ExecutionContext actor = FlowActor.writeCheckContext(state, effectiveContext);
        String conceptName = KernelRunner.resolveInvariantConceptName(step, flow);
        if (actor == null || conceptName == null || conceptName.isBlank()) {
            return null;
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : payload.entrySet()) {
            data.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        Object rawId = data.get("id");
        String id = rawId == null || String.valueOf(rawId).isBlank() ? null : String.valueOf(rawId).trim();
        try {
            runner.conceptGateway().authorizeFlowWrite(
                    conceptName, id, data, step.getConceptWrite().isUpdate(), actor);
            return null;
        } catch (com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException denied) {
            return new CapabilityError(
                    "CAPABILITY_WRITE_SCOPE_DENIED",
                    denied.getMessage(),
                    CapabilityErrorKind.AUTH,
                    Map.of(
                            "capability", step.getCapability(),
                            "operation", step.getOperation(),
                            "adapterId", step.getCapabilityAdapterId() == null ? "" : step.getCapabilityAdapterId(),
                            "concept", conceptName,
                            "gatewayCode", denied.code()
                    )
            );
        }
    }
}
