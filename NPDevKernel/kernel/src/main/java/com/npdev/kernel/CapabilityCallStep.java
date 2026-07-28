package com.npdev.kernel;

import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.events.EventEnvelope;
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
}
