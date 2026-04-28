package com.npdev.kernel;

import com.npdev.kernel.errors.ErrorKind;
import com.npdev.kernel.errors.FailureCodes;
import com.npdev.kernel.errors.FailureInfo;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.InvariantEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExecutionResult {
    private final String executionId;
    private final String correlationId;
    private final String traceId;
    private final String flowName;
    private final ExecutionStatus status;
    private final Object output;
    private final List<InvariantEngine.Violation> invariantViolations;
    private final List<EventEnvelope> emittedEvents;
    private final String capabilityStepName;
    private final Integer capabilityStepIndex;
    private final String capabilityName;
    private final String capabilityOperation;
    private final String capabilityAdapterId;
    private final CapabilityError capabilityError;
    private final String awaitedStepName;
    private final Integer awaitedStepIndex;
    private final String awaitedEventName;
    private final String awaitedCorrelationId;
    private final String errorCode;
    private final FailureInfo failureInfo;
    private final List<InputValidationError> inputValidationErrors;
    private final String error;

    private ExecutionResult(
            String executionId,
            String correlationId,
            String traceId,
            String flowName,
            ExecutionStatus status,
            Object output,
            List<InvariantEngine.Violation> invariantViolations,
            List<EventEnvelope> emittedEvents,
            String capabilityStepName,
            Integer capabilityStepIndex,
            String capabilityName,
            String capabilityOperation,
            String capabilityAdapterId,
            CapabilityError capabilityError,
            String awaitedStepName,
            Integer awaitedStepIndex,
            String awaitedEventName,
            String awaitedCorrelationId,
            String errorCode,
            FailureInfo failureInfo,
            List<InputValidationError> inputValidationErrors,
            String error
    ) {
        this.executionId = executionId;
        this.correlationId = correlationId;
        this.traceId = traceId;
        this.flowName = flowName;
        this.status = status;
        this.output = output;
        this.invariantViolations = Collections.unmodifiableList(new ArrayList<>(invariantViolations));
        this.emittedEvents = Collections.unmodifiableList(new ArrayList<>(emittedEvents));
        this.capabilityStepName = capabilityStepName;
        this.capabilityStepIndex = capabilityStepIndex;
        this.capabilityName = capabilityName;
        this.capabilityOperation = capabilityOperation;
        this.capabilityAdapterId = capabilityAdapterId;
        this.capabilityError = capabilityError;
        this.awaitedStepName = awaitedStepName;
        this.awaitedStepIndex = awaitedStepIndex;
        this.awaitedEventName = awaitedEventName;
        this.awaitedCorrelationId = awaitedCorrelationId;
        this.errorCode = errorCode;
        this.failureInfo = failureInfo;
        this.inputValidationErrors = Collections.unmodifiableList(new ArrayList<>(inputValidationErrors));
        this.error = error;
    }

    public static ExecutionResult ok(String flowName, Object output, List<EventEnvelope> emittedEvents) {
        return ok(flowName, output, emittedEvents, null, null, null);
    }

    public static ExecutionResult ok(
            String flowName,
            Object output,
            List<EventEnvelope> emittedEvents,
            String executionId,
            String correlationId,
            String traceId
    ) {
        Objects.requireNonNull(flowName, "flowName");
        Objects.requireNonNull(emittedEvents, "emittedEvents");
        return new ExecutionResult(
                executionId,
                correlationId,
                traceId,
                flowName,
                ExecutionStatus.OK,
                output,
                List.of(),
                emittedEvents,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null
        );
    }

    public static ExecutionResult failed(
            String flowName,
            List<InvariantEngine.Violation> violations,
            List<EventEnvelope> emittedEvents,
            String error
    ) {
        return failed(flowName, violations, emittedEvents, error, null, null, null);
    }

    public static ExecutionResult failed(
            String flowName,
            List<InvariantEngine.Violation> violations,
            List<EventEnvelope> emittedEvents,
            String error,
            String executionId,
            String correlationId,
            String traceId
    ) {
        Objects.requireNonNull(flowName, "flowName");
        Objects.requireNonNull(violations, "violations");
        Objects.requireNonNull(emittedEvents, "emittedEvents");
        return new ExecutionResult(
                executionId,
                correlationId,
                traceId,
                flowName,
                ExecutionStatus.FAILED,
                null,
                violations,
                emittedEvents,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                FailureCodes.SYSTEM_EXCEPTION,
                failedFailureInfo(error),
                List.of(),
                error
        );
    }


    public static ExecutionResult forbidden(
            String flowName,
            String message,
            String executionId,
            String correlationId,
            String traceId
    ) {
        Objects.requireNonNull(flowName, "flowName");
        return new ExecutionResult(
                executionId,
                correlationId,
                traceId,
                flowName,
                ExecutionStatus.FAILED,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                FailureCodes.FORBIDDEN,
                FailureInfo.of(ErrorKind.FORBIDDEN, FailureCodes.FORBIDDEN, message),
                List.of(),
                message
        );
    }

    public static ExecutionResult invariantFailed(
            String flowName,
            List<InvariantEngine.Violation> violations,
            List<EventEnvelope> emittedEvents,
            String error
    ) {
        return invariantFailed(flowName, violations, emittedEvents, error, null, null, null);
    }

    public static ExecutionResult invariantFailed(
            String flowName,
            List<InvariantEngine.Violation> violations,
            List<EventEnvelope> emittedEvents,
            String error,
            String executionId,
            String correlationId,
            String traceId
    ) {
        Objects.requireNonNull(flowName, "flowName");
        Objects.requireNonNull(violations, "violations");
        Objects.requireNonNull(emittedEvents, "emittedEvents");
        return new ExecutionResult(
                executionId,
                correlationId,
                traceId,
                flowName,
                ExecutionStatus.INVARIANT_FAILED,
                null,
                violations,
                emittedEvents,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                FailureCodes.INVARIANT_VIOLATION,
                FailureInfo.of(
                        ErrorKind.INVARIANT_VIOLATION,
                        FailureCodes.INVARIANT_VIOLATION,
                        error == null ? "Invariant validation failed" : error
                ),
                List.of(),
                error
        );
    }

    public static ExecutionResult capabilityFailed(
            String flowName,
            List<EventEnvelope> emittedEvents,
            String stepName,
            int stepIndex,
            String capabilityName,
            String capabilityOperation,
            String capabilityAdapterId,
            CapabilityError capabilityError
    ) {
        return capabilityFailed(
                flowName,
                emittedEvents,
                stepName,
                stepIndex,
                capabilityName,
                capabilityOperation,
                capabilityAdapterId,
                capabilityError,
                null,
                null,
                null
        );
    }

    public static ExecutionResult capabilityFailed(
            String flowName,
            List<EventEnvelope> emittedEvents,
            String stepName,
            int stepIndex,
            String capabilityName,
            String capabilityOperation,
            String capabilityAdapterId,
            CapabilityError capabilityError,
            String executionId,
            String correlationId,
            String traceId
    ) {
        Objects.requireNonNull(flowName, "flowName");
        Objects.requireNonNull(emittedEvents, "emittedEvents");
        Objects.requireNonNull(capabilityError, "capabilityError");
        FailureInfo failureInfo = capabilityError.toFailureInfo();

        return new ExecutionResult(
                executionId,
                correlationId,
                traceId,
                flowName,
                ExecutionStatus.CAPABILITY_FAILED,
                null,
                List.of(),
                emittedEvents,
                stepName,
                stepIndex,
                capabilityName,
                capabilityOperation,
                capabilityAdapterId,
                capabilityError,
                null,
                null,
                null,
                null,
                failureInfo.code(),
                failureInfo,
                List.of(),
                capabilityError.message()
        );
    }

    public static ExecutionResult waitingEvent(
            String flowName,
            List<EventEnvelope> emittedEvents,
            String stepName,
            int stepIndex,
            String awaitedEventName,
            String awaitedCorrelationId,
            String error,
            String executionId,
            String correlationId,
            String traceId
    ) {
        Objects.requireNonNull(flowName, "flowName");
        Objects.requireNonNull(emittedEvents, "emittedEvents");
        Objects.requireNonNull(awaitedEventName, "awaitedEventName");

        return new ExecutionResult(
                executionId,
                correlationId,
                traceId,
                flowName,
                ExecutionStatus.WAITING_EVENT,
                null,
                List.of(),
                emittedEvents,
                null,
                null,
                null,
                null,
                null,
                null,
                stepName,
                stepIndex,
                awaitedEventName,
                awaitedCorrelationId,
                null,
                null,
                List.of(),
                error
        );
    }

    public static ExecutionResult eventPersistFailed(
            String flowName,
            List<EventEnvelope> emittedEvents,
            String stepName,
            int stepIndex,
            String eventName,
            String error,
            String executionId,
            String correlationId,
            String traceId
    ) {
        Objects.requireNonNull(flowName, "flowName");
        Objects.requireNonNull(emittedEvents, "emittedEvents");

        return new ExecutionResult(
                executionId,
                correlationId,
                traceId,
                flowName,
                ExecutionStatus.EVENT_PERSIST_FAILED,
                null,
                List.of(),
                emittedEvents,
                null,
                null,
                null,
                null,
                null,
                null,
                stepName,
                stepIndex,
                eventName,
                correlationId,
                FailureCodes.SYSTEM_EXCEPTION,
                FailureInfo.of(
                        ErrorKind.SYSTEM,
                        FailureCodes.SYSTEM_EXCEPTION,
                        error == null ? "Event persistence failed" : error
                ),
                List.of(),
                error
        );
    }

    public static ExecutionResult eventPayloadInvalid(
            String flowName,
            List<EventEnvelope> emittedEvents,
            String stepName,
            int stepIndex,
            String eventName,
            List<InputValidationError> validationErrors,
            String executionId,
            String correlationId,
            String traceId
    ) {
        Objects.requireNonNull(flowName, "flowName");
        Objects.requireNonNull(emittedEvents, "emittedEvents");
        Objects.requireNonNull(eventName, "eventName");

        String errorMessage = "Event payload validation failed for " + eventName;

        String validationSummary;
        if (validationErrors == null || validationErrors.isEmpty()) {
            validationSummary = "";
        } else {
            validationSummary = validationErrors.stream()
                    .map(e -> e == null
                            ? ""
                            : Objects.toString(e.field(), "") + ":" + Objects.toString(e.code(), "") + ":" + Objects.toString(e.message(), ""))
                    .filter(s -> !s.isBlank())
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("");
        }

        FailureInfo failureInfo = FailureInfo.of(
                ErrorKind.INPUT_VALIDATION,
                FailureCodes.EVENT_PAYLOAD_INVALID,
                errorMessage,
                Map.of(
                        "eventName", eventName,
                        "stepName", stepName == null ? "" : stepName,
                        "stepIndex", String.valueOf(stepIndex),
                        "validationErrors", validationSummary
                )
        );

        return new ExecutionResult(
                executionId,
                correlationId,
                traceId,
                flowName,
                ExecutionStatus.EVENT_PAYLOAD_INVALID,
                null,
                List.of(),
                emittedEvents,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                FailureCodes.EVENT_PAYLOAD_INVALID,
                failureInfo,
                validationErrors == null ? List.of() : validationErrors,
                errorMessage
        );
    }

    public static ExecutionResult inputValidationFailed(
            String flowName,
            List<InputValidationError> validationErrors,
            List<EventEnvelope> emittedEvents,
            String error,
            String executionId,
            String correlationId,
            String traceId
    ) {
        Objects.requireNonNull(flowName, "flowName");
        Objects.requireNonNull(validationErrors, "validationErrors");
        Objects.requireNonNull(emittedEvents, "emittedEvents");
        return new ExecutionResult(
                executionId,
                correlationId,
                traceId,
                flowName,
                ExecutionStatus.INPUT_VALIDATION_FAILED,
                null,
                List.of(),
                emittedEvents,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "INPUT_VALIDATION_FAILED",
                FailureInfo.of(
                        ErrorKind.INPUT_VALIDATION,
                        FailureCodes.INPUT_VALIDATION_FAILED,
                        error == null ? "Input validation failed" : error
                ),
                validationErrors,
                error
        );
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getFlowName() {
        return flowName;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public Object getOutput() {
        return output;
    }

    public List<InvariantEngine.Violation> getInvariantViolations() {
        return invariantViolations;
    }

    public List<EventEnvelope> getEmittedEvents() {
        return emittedEvents;
    }

    public String getError() {
        return error;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<InputValidationError> getInputValidationErrors() {
        return inputValidationErrors;
    }

    public FailureInfo getFailureInfo() {
        return failureInfo;
    }

    public String getCapabilityStepName() {
        return capabilityStepName;
    }

    public Integer getCapabilityStepIndex() {
        return capabilityStepIndex;
    }

    public String getCapabilityName() {
        return capabilityName;
    }

    public String getCapabilityOperation() {
        return capabilityOperation;
    }

    public String getCapabilityAdapterId() {
        return capabilityAdapterId;
    }

    public CapabilityError getCapabilityError() {
        return capabilityError;
    }

    public String getAwaitedStepName() {
        return awaitedStepName;
    }

    public Integer getAwaitedStepIndex() {
        return awaitedStepIndex;
    }

    public String getAwaitedEventName() {
        return awaitedEventName;
    }

    public String getAwaitedCorrelationId() {
        return awaitedCorrelationId;
    }

    private static FailureInfo failedFailureInfo(String error) {
        return FailureInfo.of(
                ErrorKind.SYSTEM,
                FailureCodes.SYSTEM_EXCEPTION,
                error == null ? "Execution failed" : error
        );
    }
}