package com.npdev.kernel;

import com.npdev.kernel.errors.ErrorKind;
import com.npdev.kernel.errors.FailureCodes;
import com.npdev.kernel.errors.FailureInfo;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;

import java.util.List;
import java.util.Map;

import static com.npdev.kernel.KernelRunner.LOG;
import static com.npdev.kernel.KernelRunner.newEnvelope;
import static com.npdev.kernel.KernelRunner.nowEpochMillis;

/**
 * CARVE-4 (pure mechanical extraction, behaviour-preserving): the flow-run outcome handling split
 * out of {@link KernelRunner} -- the REG-238 resume-time flow-shape refusal, the failure-to-terminal-
 * status mapping, the operational (ops) failure events, and the structured flow-outcome log line.
 * Method bodies are the KernelRunner ones; the only edits are the {@code runner} parameter and the
 * {@code runner.} qualifier on the collaborators that used to be {@code this} fields. {@link
 * KernelRunner} keeps delegating, so its API is unchanged. Flat sibling in {@code
 * com.npdev.kernel} like {@code ResumeCoordinator}, because those collaborators are package-private.
 */
final class FlowRunOutcomes {

    private FlowRunOutcomes() {
    }

    /**
     * REG-238 (boundary B28): refuse to resume a durable instance positionally against a flow whose
     * step SHAPE changed since that instance was checkpointed.
     *
     * <p>Returns {@code null} to allow the resume, or a terminal failure result to refuse it.
     *
     * <p><b>Why this writes STUCK to the store rather than just returning a failed result.</b>
     * Neither resume caller treats a returned FAILED as a reason to stop: the sweep
     * ({@code ResumeCoordinator.resumeAllWaitingExecutions}) only persists backoff when the result
     * is WAITING_EVENT or a RuntimeException escapes, and otherwise counts the attempt as a
     * successful resume; the event-arrival path ({@code ResumeCoordinator.resumeWaitingExecutionsFor})
     * does the same with no backoff and no claim lease at all. A refusal that left the row in
     * WAITING_EVENT would therefore be re-selected and refused again indefinitely -- every sweep
     * tick on the in-proc store, and about once per 30s claim lease on the JDBC one. Writing STUCK
     * takes the row out of WAITING_EVENT, so both paths stop selecting it, and it does so at the
     * one choke point both of them funnel through.
     *
     * <p><b>Why STUCK specifically</b>, and not FAILED/FAILED_PERMANENT: STUCK is the only status
     * with a coded operator recovery path ({@link #unstickExecution}), which is exactly the
     * "fix the model, then put it back" situation this is. {@code markStuck} also preserves
     * {@code waitingForEventName}, which {@code withUnstuck} requires.
     *
     * <p><b>No awaited event is burned.</b> This returns before any step runs, and the awaited
     * event is only marked consumed inside the await step itself, so after the operator restores
     * the shape and un-sticks the instance, the original event is still there to resume on.
     *
     * <p><b>Instances checkpointed before this guard shipped</b> carry no fingerprint and are
     * allowed through, once, with a log line. They are deliberately NOT stamped with the current
     * shape on the way past: stamping at resume time would bless whatever shape happens to be live
     * at that moment as if it were the checkpointed one, laundering the exact misapplication this
     * guard exists to catch. A correct backfill would have to know the shape as of checkpoint time,
     * which by construction no longer exists. Such instances drain out under the old behaviour.
     */
    static ExecutionResult refuseIfFlowShapeChanged(KernelRunner runner, FlowInstance existing, FlowDefinition flow) {
        Object checkpointed = existing.state().get(FlowShapeFingerprint.STATE_KEY);
        String checkpointedShape = checkpointed == null ? null : checkpointed.toString();
        if (checkpointedShape == null || checkpointedShape.isBlank()) {
            LOG.info("Resuming execution " + existing.executionId() + " (flow " + existing.flowName()
                    + ") without a flow-shape fingerprint -- it was checkpointed before REG-238's guard "
                    + "existed, so a step-shape change since then cannot be detected for this instance.");
            return null;
        }

        String currentShape = flow.getShapeFingerprint();
        if (checkpointedShape.equals(currentShape)) {
            return null;
        }

        String message = "Flow \"" + existing.flowName() + "\" changed step shape since execution "
                + existing.executionId() + " was checkpointed at step index " + existing.currentStepIndex()
                + ", so that index no longer identifies the step it parked on; refusing to resume it "
                + "positionally (checkpointed shape " + checkpointedShape + ", current shape " + currentShape
                + "). Restore the flow's step shape and then un-stick this execution; un-sticking it "
                + "without fixing the model first will simply refuse again.";
        FailureInfo failureInfo = FailureInfo.of(
                ErrorKind.SYSTEM,
                FailureCodes.FLOW_SHAPE_CHANGED,
                message,
                Map.of(
                        "checkpointedShape", checkpointedShape,
                        "currentShape", currentShape,
                        "currentStepIndex", String.valueOf(existing.currentStepIndex())
                )
        );
        FlowInstance stuck = existing.markStuck(existing.state(), nowEpochMillis(), failureInfo);
        runner.flowInstanceStore.update(stuck);
        emitOperationalFailureEvent(runner, stuck);
        LOG.warning(message);
        return ExecutionResult.failed(
                existing.flowName(),
                List.of(),
                List.of(),
                message,
                existing.executionId(),
                existing.correlationId(),
                existing.executionId()
        );
    }

    static FlowInstanceStatus resolveFailureTerminalStatus(ExecutionResult failure) {
        if (failure == null || failure.getStatus() == null) {
            return FlowInstanceStatus.FAILED;
        }
        if (failure.getFailureInfo() != null
                && FailureCodes.RESUME_ATTEMPT_CAP.equals(failure.getFailureInfo().code())) {
            return FlowInstanceStatus.STUCK;
        }
        return switch (failure.getStatus()) {
            case INPUT_VALIDATION_FAILED, INVARIANT_FAILED, EVENT_PAYLOAD_INVALID -> FlowInstanceStatus.FAILED_PERMANENT;
            case CAPABILITY_FAILED -> {
                CapabilityError capabilityError = failure.getCapabilityError();
                if (capabilityError == null || capabilityError.kind() == null) {
                    yield FlowInstanceStatus.FAILED;
                }
                yield switch (capabilityError.kind()) {
                    case CONTRACT, AUTH, PERMANENT, NOT_FOUND -> FlowInstanceStatus.FAILED_PERMANENT;
                    case TRANSIENT, RATE_LIMIT, TIMEOUT -> FlowInstanceStatus.FAILED;
                };
            }
            case EVENT_PERSIST_FAILED, FAILED -> FlowInstanceStatus.FAILED;
            case WAITING_EVENT, OK -> FlowInstanceStatus.FAILED;
        };
    }

    static void emitOperationalFailureEvent(KernelRunner runner, FlowInstance instance) {
        if (instance == null) {
            return;
        }
        if (instance.status() == FlowInstanceStatus.FAILED_PERMANENT) {
            publishOperationalEvent(
                    runner,
                    "ExecutionFailedPermanent",
                    Map.of(
                            "executionId", instance.executionId(),
                            "correlationId", instance.correlationId(),
                            "flowName", instance.flowName(),
                            "errorKind", instance.lastErrorKind() == null ? "SYSTEM" : instance.lastErrorKind(),
                            "errorCode", instance.lastErrorCode() == null ? FailureCodes.SYSTEM_EXCEPTION : instance.lastErrorCode(),
                            "failedAtEpochMs", instance.failedAtEpochMs() == null ? nowEpochMillis() : instance.failedAtEpochMs()
                    ),
                    instance
            );
            return;
        }
        if (instance.status() == FlowInstanceStatus.STUCK) {
            publishOperationalEvent(
                    runner,
                    "ExecutionStuck",
                    Map.of(
                            "executionId", instance.executionId(),
                            "correlationId", instance.correlationId(),
                            "flowName", instance.flowName(),
                            "resumeAttemptCount", instance.resumeAttemptCount(),
                            "lastResumeErrorCode", instance.lastResumeErrorCode() == null ? FailureCodes.RESUME_ATTEMPT_CAP : instance.lastResumeErrorCode(),
                            "lastProgressAtEpochMs", instance.lastProgressAtEpochMs() == null ? 0L : instance.lastProgressAtEpochMs()
                    ),
                    instance
            );
        }
    }

    private static void publishOperationalEvent(
            KernelRunner runner,
            String eventName,
            Map<String, Object> payload,
            FlowInstance instance
    ) {
        try {
            EventEnvelope envelope = newEnvelope(
                    runner.nextId("event"),
                    eventName,
                    instance.correlationId(),
                    "system:" + instance.executionId(),
                    payload,
                    Map.of(
                            "flow", instance.flowName(),
                            "executionId", instance.executionId(),
                            "eventCategory", "ops"
                    ),
                    instance.flowName(),
                    Math.max(0, instance.currentStepIndex()),
                    instance.tenantId(),
                    instance.actorId()
            );
            if (runner.eventStore != null) {
                runner.eventStore.append(envelope);
            }
            runner.eventBus.publish(envelope);
        } catch (RuntimeException ignored) {
            // Ops event emission must never alter execution outcome.
        }
    }

    static void logFlowOutcome(String flowName, ExecutionResult result, String outcome, long durationMs) {
        LOG.info(String.format(
                "{\"event\":\"npdev.flow.outcome\",\"flowName\":\"%s\",\"executionId\":\"%s\","
                        + "\"correlationId\":\"%s\",\"outcome\":\"%s\",\"status\":\"%s\",\"durationMs\":%d}",
                jsonEscape(flowName),
                jsonEscape(result.getExecutionId()),
                jsonEscape(result.getCorrelationId()),
                jsonEscape(outcome),
                result.getStatus() == null ? "" : result.getStatus().name(),
                durationMs
        ));
    }

    static String flowOutcomeTag(ExecutionStatus status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status) {
            case OK -> "success";
            case WAITING_EVENT -> "waiting";
            default -> "failure";
        };
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
