package com.npdev.generator.emitters;

/**
 * Static Java source template for {@code GeneratedFlowCodaRunner}: the generated class that
 * starts a start-endpoint flow and resumes it on incoming events, mirroring the action kernel
 * runner's idempotency/evidence/correlation handling for the flow-execution surface.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2) into its own file purely because of size
 * -- this is one pure, parameterless string-literal method.
 */
final class TrustedFlowCodaRunnerTemplate {

    private TrustedFlowCodaRunnerTemplate() {
    }

    static String generatedFlowCodaRunnerSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.generated.runtime.service.KernelFacade;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.ExecutionResult;
                import com.npdev.kernel.ExecutionStatus;
                import com.npdev.kernel.events.EventEnvelope;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptListRequest;
                import com.npdev.kernel.execution.FlowInstance;
                import org.springframework.stereotype.Service;

                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import java.util.concurrent.ConcurrentHashMap;

                @Service
                public class GeneratedFlowCodaRunner {
                    private final KernelFacade kernelFacade;
                    private final ConceptGateway conceptGateway;
                    private final Map<String, GeneratedFlowExecutionResponse> flowStartIdempotencyCache = new ConcurrentHashMap<>();

                    public GeneratedFlowCodaRunner(KernelFacade kernelFacade, ConceptGateway conceptGateway) {
                        this.kernelFacade = kernelFacade;
                        this.conceptGateway = conceptGateway;
                    }

                    private static String flowStartIdempotencyKey(
                            String flowName,
                            GeneratedFlowExecutionRequest request
                    ) {
                        if (request == null || request.idempotencyKey().isBlank()) {
                            return "";
                        }
                        String safeFlowName = flowName == null ? "" : flowName.trim();
                        return safeFlowName + "::" + request.idempotencyKey();
                    }

                    private GeneratedFlowExecutionResponse rememberFlowStartResponse(
                            String flowStartIdempotencyKey,
                            GeneratedFlowExecutionResponse response
                    ) {
                        if (response == null) {
                            return null;
                        }
                        if (flowStartIdempotencyKey == null || flowStartIdempotencyKey.isBlank()) {
                            return response.withFlowStartIdempotencyStatus("unavailable: no idempotencyKey supplied");
                        }
                        GeneratedFlowExecutionResponse recorded = response.withFlowStartIdempotencyStatus("recorded: generated flow-start idempotency guard");
                        GeneratedFlowExecutionResponse existing = flowStartIdempotencyCache.putIfAbsent(flowStartIdempotencyKey, recorded);
                        return existing == null ? recorded : existing.asFlowStartIdempotencyReplay();
                    }

                    public GeneratedFlowExecutionResponse start(
                            String flowName,
                            GeneratedFlowExecutionRequest request,
                            ExecutionContext context
                    ) {
                        GeneratedFlowDescriptor descriptor = GeneratedFlowRegistry.find(flowName);
                        GeneratedFlowExecutionRequest safeRequest = request == null
                                ? new GeneratedFlowExecutionRequest("", "", "", Map.of())
                                : request;
                        ExecutionContext safeContext = context == null ? ExecutionContext.anonymous() : context;
                        String flowStartIdempotencyKey = flowStartIdempotencyKey(descriptor == null ? flowName : descriptor.flowName(), safeRequest);
                        if (!flowStartIdempotencyKey.isBlank()) {
                            GeneratedFlowExecutionResponse cached = flowStartIdempotencyCache.get(flowStartIdempotencyKey);
                            if (cached != null) {
                                return cached.asFlowStartIdempotencyReplay();
                            }
                        }
                        if (descriptor == null) {
                            GeneratedFlowExecutionResponse response = new GeneratedFlowExecutionResponse(
                                    "rejected",
                                    flowName,
                                    "",
                                    "not_found",
                                    safeRequest.executionId(),
                                    safeRequest.correlationId(),
                                    "",
                                    "",
                                    "",
                                    "unavailable: flow not found before dispatch",
                                    0,
                                    0,
                                    0,
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "",
                                    "unknown-flow",
                                    "unknown-flow",
                                    Map.of()
                            );
                            return rememberFlowStartResponse(flowStartIdempotencyKey, response);
                        }
                        GeneratedActionDescriptor actionDescriptor = GeneratedActionRegistry.find(descriptor.actionName());
                        String sideEffectConcept = actionDescriptor == null ? "" : actionDescriptor.sideEffectConcept();
                        int sideEffectCountBefore = countSideEffects(sideEffectConcept, safeContext);
                        try {
                            ExecutionResult result = kernelFacade.executeFlow(
                                    descriptor.flowName(),
                                    safeRequest.toKernelInput(),
                                    safeContext
                            );
                            Optional<FlowInstance> instance = result.getExecutionId() == null
                                    ? Optional.empty()
                                    : kernelFacade.findExecution(result.getExecutionId(), safeContext);
                            Map<String, Object> actionResult = result.getOutput() instanceof GeneratedActionCapabilityResult capabilityResult
                                    ? capabilityResult.toMap()
                                    : result.getOutput() instanceof Map<?, ?> map
                                    ? toStringMap(map)
                                    : Map.of();
                            int sideEffectCountAfter = countSideEffects(sideEffectConcept, safeContext);
                            int createdCount = Math.max(0, sideEffectCountAfter - sideEffectCountBefore);
                            String flowStatus = instance.map(row -> row.status().name()).orElse(statusValue(result));
                            String correlationId = firstNonBlank(result.getCorrelationId(), safeRequest.correlationId());
                            String executionId = firstNonBlank(result.getExecutionId(), safeRequest.executionId());
                            String evidenceUrl = correlationId.isBlank()
                                    ? ""
                                    : "/generated/flows/correlations/" + urlToken(correlationId);
                            boolean waiting = result.getStatus() == ExecutionStatus.WAITING_EVENT || "WAITING_EVENT".equals(flowStatus);
                            String dispatchStatus = flowStatus.equals("COMPLETED")
                                    ? "dispatched: KernelRunner -> CapabilityDispatcher -> GeneratedActionCapabilityAdapter"
                                    : waiting
                                    ? "waiting: KernelRunner persisted WAITING_EVENT before generated action dispatch"
                                    : "failed: KernelRunner capability dispatch did not complete";
                            if (flowStatus.equals("COMPLETED")
                                    && !safeRequest.idempotencyKey().isBlank()
                                    && createdCount == 0) {
                                dispatchStatus = "prevented: action idempotency reused before generated action adapter dispatch";
                            }
                            String responseStatus = waiting ? "waiting" : result.getStatus() == ExecutionStatus.OK ? "ok" : "error";
                            Map<String, Object> enrichedResult = new LinkedHashMap<>(actionResult);
                            if (waiting) {
                                enrichedResult.put("waitingStatus", "WAITING_EVENT");
                                enrichedResult.put("resumeEndpoint", "/generated/flows/" + urlToken(descriptor.flowName()) + "/resume");
                                enrichedResult.put("eventEndpointTemplate", "/generated/flows/" + urlToken(descriptor.flowName()) + "/events/{eventName}");
                            }
                            GeneratedFlowExecutionResponse response = new GeneratedFlowExecutionResponse(
                                    responseStatus,
                                    descriptor.flowName(),
                                    executionId,
                                    flowStatus,
                                    executionId,
                                    correlationId,
                                    descriptor.actionName(),
                                    actionDescriptor == null ? "" : actionDescriptor.procedureName(),
                                    actionDescriptor == null ? "generated.action." + descriptor.actionName() : actionDescriptor.capabilityId(),
                                    dispatchStatus,
                                    createdCount,
                                    sideEffectCountBefore,
                                    sideEffectCountAfter,
                                    waiting
                                            ? "unavailable: flow is waiting before generated action success event"
                                            : "written: kernel flow/action evidence available through JDBC proof",
                                    "written: KernelFacade executeFlow audit path",
                                    waiting
                                            ? "written: KernelRunner WAITING_EVENT trace path"
                                            : "written: KernelRunner flow trace path",
                                    safeRequest.idempotencyKey().isBlank()
                                            ? "unavailable: no idempotencyKey supplied"
                                            : waiting
                                            ? "deferred: action idempotency pending resume"
                                            : "recorded: KernelRunner capability idempotency policy",
                                    "owned: KernelRunner correlation ownership path",
                                    evidenceUrl,
                                    waiting ? "flow waiting for event" : result.getStatus() == ExecutionStatus.OK ? "flow completed" : "flow failed",
                                    waiting ? "" : result.getError() == null ? "" : result.getError(),
                                    enrichedResult
                            );
                            return rememberFlowStartResponse(flowStartIdempotencyKey, response);
                        } catch (RuntimeException exception) {
                            return new GeneratedFlowExecutionResponse(
                                    "error",
                                    descriptor.flowName(),
                                    safeRequest.executionId(),
                                    "FAILED",
                                    safeRequest.executionId(),
                                    safeRequest.correlationId(),
                                    descriptor.actionName(),
                                    "",
                                    "generated.action." + descriptor.actionName(),
                                    "failed: " + exception.getClass().getSimpleName(),
                                    0,
                                    0,
                                    0,
                                    "unavailable: flow failed before success event proof",
                                    "failed: KernelFacade executeFlow threw",
                                    "failed: KernelRunner flow execution threw",
                                    safeRequest.idempotencyKey().isBlank()
                                            ? "unavailable: no idempotencyKey supplied"
                                            : "failed: flow failed before idempotency proof",
                                    "unavailable: flow failed before correlation proof",
                                    "",
                                    "flow failed",
                                    exception.getClass().getSimpleName() + ": " + clean(exception.getMessage()),
                                    Map.of()
                            );
                        }
                    }

                    public GeneratedFlowExecutionResponse publishEventAndResume(
                            String flowName,
                            String eventName,
                            Map<String, Object> body,
                            ExecutionContext context
                    ) {
                        GeneratedFlowDescriptor descriptor = GeneratedFlowRegistry.find(flowName);
                        ExecutionContext safeContext = context == null ? ExecutionContext.anonymous() : context;
                        Map<String, Object> input = body == null ? Map.of() : new LinkedHashMap<>(body);
                        String executionId = stringValue(input.remove("executionId"));
                        String correlationId = stringValue(input.remove("correlationId"));
                        String idempotencyKey = stringValue(input.remove("idempotencyKey"));
                        String causationId = firstNonBlank(stringValue(input.remove("causationId")), executionId);
                        String requestedEventName = firstNonBlank(eventName, stringValue(input.remove("eventName")));
                        if (descriptor == null) {
                            return new GeneratedFlowExecutionResponse(
                                    "rejected",
                                    flowName,
                                    executionId,
                                    "not_found",
                                    executionId,
                                    correlationId,
                                    "",
                                    "",
                                    "",
                                    "unavailable: flow not found before resume event",
                                    0,
                                    0,
                                    0,
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "",
                                    "unknown-flow",
                                    "unknown-flow",
                                    Map.of()
                            );
                        }
                        if (requestedEventName.isBlank()) {
                            return new GeneratedFlowExecutionResponse(
                                    "rejected",
                                    descriptor.flowName(),
                                    executionId,
                                    "missing_event",
                                    executionId,
                                    correlationId,
                                    descriptor.actionName(),
                                    "",
                                    "generated.action." + descriptor.actionName(),
                                    "unavailable: resume eventName missing",
                                    0,
                                    0,
                                    0,
                                    "unavailable: event not published",
                                    "unavailable: eventName missing",
                                    "unavailable: eventName missing",
                                    "unavailable: eventName missing",
                                    "unavailable: eventName missing",
                                    "",
                                    "missing-event-name",
                                    "missing-event-name",
                                    Map.of("resumeStatus", "missing_event")
                            );
                        }
                        GeneratedActionDescriptor actionDescriptor = GeneratedActionRegistry.find(descriptor.actionName());
                        String sideEffectConcept = actionDescriptor == null ? "" : actionDescriptor.sideEffectConcept();
                        int sideEffectCountBefore = countSideEffects(sideEffectConcept, safeContext);
                        try {
                            EventEnvelope envelope = kernelFacade.publishExternalEvent(
                                    requestedEventName,
                                    correlationId,
                                    causationId,
                                    input,
                                    safeContext
                            );
                            String effectiveCorrelationId = firstNonBlank(envelope.correlationId(), correlationId);
                            Optional<FlowInstance> instance = executionId.isBlank()
                                    ? Optional.empty()
                                    : kernelFacade.findExecution(executionId, safeContext);
                            ExecutionResult resumeExecutionResult = null;
                            if (instance.isPresent()
                                    && "WAITING_EVENT".equals(instance.get().status().name())
                                    && !executionId.isBlank()) {
                                resumeExecutionResult = kernelFacade.resumeExecution(executionId, safeContext);
                                instance = kernelFacade.findExecution(executionId, safeContext);
                            }
                            int sideEffectCountAfter = countSideEffects(sideEffectConcept, safeContext);
                            int createdCount = Math.max(0, sideEffectCountAfter - sideEffectCountBefore);
                            String flowStatus = instance.map(row -> row.status().name())
                                    .orElse(resumeExecutionResult == null ? "event_published" : statusValue(resumeExecutionResult));
                            if (resumeExecutionResult != null
                                    && resumeExecutionResult.getStatus() == ExecutionStatus.OK
                                    && !"COMPLETED".equals(flowStatus)) {
                                flowStatus = "COMPLETED";
                            }
                            String evidenceUrl = effectiveCorrelationId.isBlank()
                                    ? ""
                                    : "/generated/flows/correlations/" + urlToken(effectiveCorrelationId);
                            Map<String, Object> resumeResult = new LinkedHashMap<>();
                            resumeResult.put("resumeStatus", resumeExecutionResult == null ? "event_published" : statusValue(resumeExecutionResult));
                            resumeResult.put("eventId", envelope.eventId());
                            resumeResult.put("eventName", envelope.eventName());
                            resumeResult.put("eventCorrelationId", effectiveCorrelationId);
                            resumeResult.put("resumedFlowInstanceStatus", flowStatus);
                            resumeResult.put("actionSideEffectCreatedCount", createdCount);
                            if (!idempotencyKey.isBlank()) {
                                resumeResult.put("idempotencyKey", idempotencyKey);
                            }
                            boolean completed = "COMPLETED".equals(flowStatus) || (resumeExecutionResult != null && resumeExecutionResult.getStatus() == ExecutionStatus.OK);
                            return new GeneratedFlowExecutionResponse(
                                    completed ? "ok" : "waiting",
                                    descriptor.flowName(),
                                    executionId,
                                    flowStatus,
                                    executionId,
                                    effectiveCorrelationId,
                                    descriptor.actionName(),
                                    actionDescriptor == null ? "" : actionDescriptor.procedureName(),
                                    actionDescriptor == null ? "generated.action." + descriptor.actionName() : actionDescriptor.capabilityId(),
                                    completed
                                            ? "resumed: external event -> KernelRunner.resumeExecution -> CapabilityDispatcher -> GeneratedActionCapabilityAdapter"
                                            : "published: external event stored; flow remains waiting or executionId not supplied",
                                    createdCount,
                                    sideEffectCountBefore,
                                    sideEffectCountAfter,
                                    "written: KernelFacade.publishExternalEvent stored resume event",
                                    "written: KernelFacade publish/resume audit path",
                                    completed
                                            ? "written: KernelRunner resume trace path"
                                            : "written: event publish trace/audit path; completion readback unavailable",
                                    idempotencyKey.isBlank()
                                            ? "unavailable: no idempotencyKey supplied"
                                            : completed
                                            ? "recorded: resumed generated action idempotency policy"
                                            : "deferred: action idempotency pending completed resume",
                                    "owned: KernelRunner correlation ownership path",
                                    evidenceUrl,
                                    completed ? "flow resumed and completed" : "resume event published",
                                    "",
                                    resumeResult
                            );
                        } catch (RuntimeException exception) {
                            return new GeneratedFlowExecutionResponse(
                                    "error",
                                    descriptor.flowName(),
                                    executionId,
                                    "FAILED",
                                    executionId,
                                    correlationId,
                                    descriptor.actionName(),
                                    actionDescriptor == null ? "" : actionDescriptor.procedureName(),
                                    actionDescriptor == null ? "generated.action." + descriptor.actionName() : actionDescriptor.capabilityId(),
                                    "failed: " + exception.getClass().getSimpleName(),
                                    0,
                                    sideEffectCountBefore,
                                    sideEffectCountBefore,
                                    "failed: resume event publication failed",
                                    "failed: KernelFacade publish/resume threw",
                                    "failed: KernelRunner publish/resume threw",
                                    idempotencyKey.isBlank()
                                            ? "unavailable: no idempotencyKey supplied"
                                            : "failed: resume failed before idempotency proof",
                                    "unavailable: resume failed before correlation proof",
                                    "",
                                    "flow resume failed",
                                    exception.getClass().getSimpleName() + ": " + clean(exception.getMessage()),
                                    Map.of("resumeStatus", "failed")
                            );
                        }
                    }

                    private int countSideEffects(String concept, ExecutionContext context) {
                        if (concept == null || concept.isBlank() || conceptGateway == null) {
                            return 0;
                        }
                        ExecutionContext safeContext = context == null ? ExecutionContext.anonymous() : context;
                        return conceptGateway.list(
                                new ConceptListRequest(concept, safeContext.tenantId()),
                                safeContext
                        ).size();
                    }

                    private static Map<String, Object> toStringMap(Map<?, ?> source) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : source.entrySet()) {
                            out.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        return out;
                    }

                    private static int number(Object value) {
                        if (value instanceof Number number) {
                            return number.intValue();
                        }
                        if (value == null) {
                            return 0;
                        }
                        try {
                            return Integer.parseInt(String.valueOf(value));
                        } catch (NumberFormatException ignored) {
                            return 0;
                        }
                    }

                    private static String statusValue(ExecutionResult result) {
                        return result == null || result.getStatus() == null ? "unknown" : result.getStatus().name();
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value).trim();
                    }

                    private static String firstNonBlank(String... values) {
                        if (values == null) {
                            return "";
                        }
                        for (String value : values) {
                            if (value != null && !value.isBlank()) {
                                return value.trim();
                            }
                        }
                        return "";
                    }

                    private static String urlToken(String value) {
                        return value.replace(" ", "%20");
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }
}
